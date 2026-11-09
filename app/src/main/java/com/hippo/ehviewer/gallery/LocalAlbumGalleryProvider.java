/*
 * Copyright 2026 EhViewer contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hippo.ehviewer.gallery;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageDecoder;
import android.graphics.drawable.AnimatedImageDrawable;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.hippo.ehviewer.GetText;
import com.hippo.ehviewer.R;
import com.hippo.lib.glgallery.GalleryPageView;
import com.hippo.lib.image.Image;
import com.hippo.lib.yorozuya.IOUtils;
import com.hippo.lib.yorozuya.StringUtils;
import com.hippo.lib.yorozuya.thread.PriorityThread;
import com.hippo.unifile.UniFile;
import com.hippo.util.NaturalComparator;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Stack;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 直接从 SAF 目录树（{@code content://.../tree/...}）读取图片的画廊 provider。
 *
 * <p>与 {@link DirGalleryProvider} 的区别：DirGalleryProvider 只接受真实的文件系统目录，
 * 它在解码时会把 InputStream 强转成 FileInputStream；而 DocumentFile 返回的是
 * ParcelFileDescriptor 包装流，强转会抛 ClassCastException。这里改为通用的流解码，
 * 因此可以把「导入本地相册」做成<b>引用原目录</b>而不复制任何文件。
 *
 * <p>生命周期：{@link #start()} 起一个后台线程负责列目录 + 按需解码，{@link #stop()} 中断它。
 * 文件列表只在内存里，App 被杀掉不会留下任何残留文件。
 */
public class LocalAlbumGalleryProvider extends GalleryProvider2 implements Runnable {

    private static final String TAG = LocalAlbumGalleryProvider.class.getSimpleName();
    private static final AtomicInteger sIdGenerator = new AtomicInteger();

    /** 防止深层目录树导致栈溢出。正常相册不会超过这个层数。 */
    private static final int MAX_DEPTH = 20;

    /** 单边解码上限。超过就降采样，避免手机大图直接 OOM。 */
    private static final int MAX_DECODE_SIZE = 4096;

    /** 进程内缓存最近打开的本地相册列表，避免每次进画廊都重新扫描。 */
    private static final int MAX_CACHE_ENTRIES = 2;
    private static final Object sCacheLock = new Object();
    private static final LinkedHashMap<String, CachedList> sCache =
            new LinkedHashMap<String, CachedList>(4, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, CachedList> eldest) {
                    return size() > MAX_CACHE_ENTRIES;
                }
            };

    private final UniFile mRoot;
    private final Stack<Integer> mRequests = new Stack<>();
    private final AtomicInteger mDecodingIndex = new AtomicInteger(GalleryPageView.INVALID_INDEX);
    private final AtomicReference<UniFile[]> mFileList = new AtomicReference<>();
    private final AtomicReference<String[]> mRelativeNames = new AtomicReference<>();

    @Nullable
    private Thread mBgThread;
    private volatile int mSize = STATE_WAIT;
    private String mError;

    public LocalAlbumGalleryProvider(@NonNull UniFile root) {
        mRoot = root;
    }

    @Override
    public void start() {
        super.start();

        mBgThread = new PriorityThread(this, TAG + '-' + sIdGenerator.incrementAndGet(),
                Process.THREAD_PRIORITY_DEFAULT);
        mBgThread.start();
    }

    @Override
    public void stop() {
        super.stop();

        if (mBgThread != null) {
            mBgThread.interrupt();
            mBgThread = null;
        }
    }

    @Override
    public int size() {
        return mSize;
    }

    @Override
    public String getError() {
        return mError;
    }

    @NonNull
    @Override
    public String getImageFilename(int index) {
        String[] names = mRelativeNames.get();
        if (names != null && index >= 0 && index < names.length) {
            return stripExtension(names[index]);
        }
        return Integer.toString(index);
    }

    @Override
    public boolean save(int index, @NonNull UniFile file) {
        UniFile src = getFile(index);
        if (src == null) {
            return false;
        }
        InputStream is = null;
        OutputStream os = null;
        try {
            is = src.openInputStream();
            os = file.openOutputStream();
            IOUtils.copy(is, os);
            return true;
        } catch (IOException | RuntimeException e) {
            return false;
        } finally {
            IOUtils.closeQuietly(is);
            IOUtils.closeQuietly(os);
        }
    }

    @Nullable
    @Override
    public UniFile save(int index, @NonNull UniFile dir, @NonNull String filename) {
        UniFile src = getFile(index);
        if (src == null) {
            return null;
        }
        String extension = getExtension(src.getName());
        UniFile dst = dir.subFile(extension != null ? filename + extension : filename);
        if (dst == null) {
            return null;
        }
        return save(index, dst) ? dst : null;
    }

    @Override
    protected void onRequest(int index) {
        synchronized (mRequests) {
            if (!mRequests.contains(index) && index != mDecodingIndex.get()) {
                mRequests.add(index);
                mRequests.notifyAll();
            }
        }
        notifyPageWait(index);
    }

    @Override
    protected void onForceRequest(int index) {
        onRequest(index);
    }

    @Override
    public void onCancelRequest(int index) {
        synchronized (mRequests) {
            mRequests.remove(Integer.valueOf(index));
        }
    }

    @Override
    public void run() {
        // 列目录可能很慢（几百上千个文件 + SAF IPC），必须放在后台线程
        UniFile[] files;
        String[] names;
        final long start = SystemClock.elapsedRealtime();

        String cacheKey = null;
        try {
            cacheKey = mRoot.getUri().toString();
        } catch (RuntimeException ignored) {
        }
        final long lastModified = getLastModified();
        final CachedList cached = getCached(cacheKey, lastModified);

        if (cached != null) {
            files = cached.files;
            names = cached.names;
            Log.i(TAG, "Cache hit: " + files.length + " images");
        } else {
            try {
                List<Entry> entries = new ArrayList<>();
                collect(mRoot, "", entries, 0);
                if (entries.isEmpty()) {
                    mSize = STATE_ERROR;
                    mError = GetText.getString(R.string.error_not_folder_path);
                    notifyDataChanged();
                    return;
                }
                // 用 Collections.sort 而不是 List.sort：后者是 API 24+ 的默认方法，
                // minSdk 23 上会 NoSuchMethodError。
                Collections.sort(entries, ENTRY_COMPARATOR);

                int count = entries.size();
                files = new UniFile[count];
                names = new String[count];
                for (int i = 0; i < count; i++) {
                    Entry entry = entries.get(i);
                    files[i] = entry.file;
                    names[i] = entry.relativeName;
                }
            } catch (RuntimeException e) {
                Log.e(TAG, "Failed to list local album", e);
                mSize = STATE_ERROR;
                mError = GetText.getString(R.string.error_reading_failed);
                notifyDataChanged();
                return;
            }

            // 只有扫描前后目录 mtime 没变才缓存，避免把正在变化的目录缓存下来。
            final long afterScan = getLastModified();
            if (afterScan > 0 && afterScan == lastModified) {
                putCached(cacheKey, afterScan, files, names);
            }
            Log.i(TAG, "Scanned " + files.length + " images in "
                    + (SystemClock.elapsedRealtime() - start) + " ms");
        }

        mFileList.lazySet(files);
        mRelativeNames.lazySet(names);
        mSize = files.length;
        notifyDataChanged();

        while (!Thread.currentThread().isInterrupted()) {
            int index;
            synchronized (mRequests) {
                if (mRequests.isEmpty()) {
                    try {
                        mRequests.wait();
                    } catch (InterruptedException e) {
                        break;
                    }
                    continue;
                }
                index = mRequests.pop();
                mDecodingIndex.lazySet(index);
            }

            if (index < 0 || index >= files.length) {
                mDecodingIndex.lazySet(GalleryPageView.INVALID_INDEX);
                notifyPageFailed(index, GetText.getString(R.string.error_out_of_range));
                continue;
            }

            try {
                Image image = decodeImage(files[index]);
                if (image != null) {
                    notifyPageSucceed(index, image);
                } else {
                    notifyPageFailed(index, GetText.getString(R.string.error_decoding_failed));
                }
            } catch (IOException | RuntimeException e) {
                notifyPageFailed(index, GetText.getString(R.string.error_reading_failed));
            } finally {
                mDecodingIndex.lazySet(GalleryPageView.INVALID_INDEX);
            }
        }

        mFileList.lazySet(null);
        mRelativeNames.lazySet(null);
        Log.i(TAG, "LocalAlbumGalleryProvider end");
    }

    /**
     * 解码一张图。
     *
     * <p>手机相册里的照片动辄 4000x3000，直接 createFromStream 会是 48MB 的位图，
     * 连续翻几页就 OOM。这里先读尺寸，超过 {@link #MAX_DECODE_SIZE} 就按 2 的幂降采样，
     * 再交给 GL 层。漫画页一般远小于这个上限，不会触发降采样。
     */
    @Nullable
    private Image decodeImage(@NonNull UniFile file) throws IOException {
        // GIF / animated WebP must go through ImageDecoder, otherwise
        // BitmapFactory only returns the first frame.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && isAnimatedImage(file.getName())) {
            Image animated = decodeAnimatedImage(file);
            if (animated != null) {
                return animated;
            }
        }

        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream is = file.openInputStream()) {
            BitmapFactory.decodeStream(is, null, bounds);
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return null;
        }

        int sampleSize = 1;
        int maxSide = Math.max(bounds.outWidth, bounds.outHeight);
        while (maxSide / sampleSize > MAX_DECODE_SIZE) {
            sampleSize *= 2;
        }

        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sampleSize;
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap bitmap;
        try (InputStream is = file.openInputStream()) {
            bitmap = BitmapFactory.decodeStream(is, null, options);
        }
        if (bitmap == null) {
            return null;
        }
        return Image.decode(new BitmapDrawable(null, bitmap), false);
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.P)
    @Nullable
    private Image decodeAnimatedImage(@NonNull UniFile file) {
        try (InputStream is = file.openInputStream()) {
            byte[] data = IOUtils.getAllByte(is);
            if (data == null || data.length == 0) {
                return null;
            }
            ImageDecoder.Source source = ImageDecoder.createSource(ByteBuffer.wrap(data));
            Drawable drawable = ImageDecoder.decodeDrawable(source, (decoder, info, src) -> {
                decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                int width = info.getSize().getWidth();
                int height = info.getSize().getHeight();
                int maxSide = Math.max(width, height);
                if (maxSide > MAX_DECODE_SIZE) {
                    float scale = (float) MAX_DECODE_SIZE / maxSide;
                    decoder.setTargetSize(
                            Math.max(1, Math.round(width * scale)),
                            Math.max(1, Math.round(height * scale)));
                }
            });
            if (drawable instanceof AnimatedImageDrawable) {
                ((AnimatedImageDrawable) drawable)
                        .setRepeatCount(AnimatedImageDrawable.REPEAT_INFINITE);
            }
            return Image.decode(drawable, false);
        } catch (Exception e) {
            Log.w(TAG, "Failed to decode animated image: " + file.getUri(), e);
            return null;
        }
    }

    private static boolean isAnimatedImage(@Nullable String name) {
        if (name == null) {
            return false;
        }
        String lower = name.toLowerCase();
        return lower.endsWith(".gif") || lower.endsWith(".webp");
    }

    @Nullable
    private UniFile getFile(int index) {
        UniFile[] files = mFileList.get();
        if (files == null || index < 0 || index >= files.length) {
            return null;
        }
        return files[index];
    }

    private long getLastModified() {
        try {
            return mRoot.lastModified();
        } catch (RuntimeException e) {
            return -1;
        }
    }

    @Nullable
    private static CachedList getCached(@Nullable String key, long lastModified) {
        if (key == null || lastModified <= 0) {
            return null;
        }
        synchronized (sCacheLock) {
            CachedList cached = sCache.get(key);
            if (cached != null && cached.lastModified == lastModified) {
                return cached;
            }
            if (cached != null) {
                sCache.remove(key);
            }
            return null;
        }
    }

    private static void putCached(@Nullable String key, long lastModified,
                                  @NonNull UniFile[] files, @NonNull String[] names) {
        if (key == null || lastModified <= 0) {
            return;
        }
        synchronized (sCacheLock) {
            sCache.put(key, new CachedList(lastModified, files, names));
        }
    }

    private static final class CachedList {
        final long lastModified;
        final UniFile[] files;
        final String[] names;

        CachedList(long lastModified, UniFile[] files, String[] names) {
            this.lastModified = lastModified;
            this.files = files;
            this.names = names;
        }
    }

    private static void collect(@Nullable UniFile dir, String prefix, List<Entry> out, int depth) {
        if (dir == null || depth > MAX_DEPTH) {
            return;
        }
        UniFile[] children;
        try {
            children = dir.listFiles();
        } catch (RuntimeException e) {
            Log.w(TAG, "Failed to list " + dir.getUri(), e);
            return;
        }
        if (children == null) {
            return;
        }
        for (UniFile child : children) {
            if (child == null) {
                continue;
            }
            String name = child.getName();
            if (name == null || name.startsWith(".")) {
                continue;
            }
            String relative = prefix.isEmpty() ? name : prefix + '/' + name;
            if (child.isDirectory()) {
                collect(child, relative, out, depth + 1);
            } else if (isImage(name)) {
                out.add(new Entry(child, relative));
            }
        }
    }

    private static boolean isImage(String name) {
        return StringUtils.endsWith(name.toLowerCase(), SUPPORT_IMAGE_EXTENSIONS);
    }

    @Nullable
    private static String getExtension(@Nullable String name) {
        if (name == null) {
            return null;
        }
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot) : null;
    }

    @NonNull
    private static String stripExtension(@NonNull String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static final class Entry {
        final UniFile file;
        final String relativeName;

        Entry(UniFile file, String relativeName) {
            this.file = file;
            this.relativeName = relativeName;
        }
    }

    private static final NaturalComparator NATURAL_COMPARATOR = new NaturalComparator();

    private static final Comparator<Entry> ENTRY_COMPARATOR = (o1, o2) ->
            NATURAL_COMPARATOR.compare(o1.relativeName, o2.relativeName);
}
