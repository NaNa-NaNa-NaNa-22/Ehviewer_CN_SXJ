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

import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.util.DisplayMetrics;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.hippo.ehviewer.GetText;
import com.hippo.ehviewer.R;
import com.hippo.lib.glgallery.GalleryPageView;
import com.hippo.lib.glgallery.GalleryProvider;
import com.hippo.lib.glview.view.GLRoot;
import com.hippo.lib.image.Image;
import com.hippo.lib.yorozuya.IOUtils;
import com.hippo.lib.yorozuya.thread.PriorityThread;
import com.hippo.unifile.UniFile;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Locale;
import java.util.Stack;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 使用系统 {@link PdfRenderer} 按页渲染 PDF。
 *
 * <p>不会把整本 PDF 读进内存，也不会一次性渲染所有页面：只有当前请求的页会被
 * 渲染成 Bitmap，随后由 {@link GalleryProvider} 的 ImageCache 管理缓存。
 * 因此大 PDF 也可以正常翻页/上下滚动。
 *
 * <p>PDF 的翻页/滚动布局复用现有 {@link com.hippo.lib.glgallery.GalleryView}：
 * 阅读方向设置为「上下滚动」时为连续滚动，设置为左右方向时为分页翻页。
 */
public class PdfGalleryProvider extends GalleryProvider2 implements Runnable {

    private static final String TAG = PdfGalleryProvider.class.getSimpleName();
    private static final AtomicInteger sIdGenerator = new AtomicInteger();

    /** 单页渲染上限，避免超大页面直接 OOM。 */
    private static final int MAX_RENDER_WIDTH = 1600;
    private static final int MAX_RENDER_HEIGHT = 3000;
    private static final long MAX_RENDER_PIXELS = 4_500_000L;

    private final Context mContext;
    private final Uri mUri;
    private final Stack<Integer> mRequests = new Stack<>();
    private final AtomicInteger mDecodingIndex = new AtomicInteger(GalleryPageView.INVALID_INDEX);
    private final Object mRendererLock = new Object();

    @Nullable
    private Thread mBgThread;
    @Nullable
    private ParcelFileDescriptor mPfd;
    @Nullable
    private PdfRenderer mRenderer;
    private volatile int mSize = STATE_WAIT;
    private volatile String mError;

    public PdfGalleryProvider(@NonNull Context context, @NonNull Uri uri) {
        mContext = context.getApplicationContext();
        mUri = uri;
    }

    public PdfGalleryProvider(@NonNull Context context, @NonNull File file) {
        this(context, Uri.fromFile(file));
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

    /**
     * GalleryActivity 是先 start() 再 setListener()/setGLRoot() 的。
     * 如果 PDF 打开得很快，首次 notifyDataChanged 可能在 listener/GLRoot 还没设置时被丢掉。
     * 这里在设置完成后补一次通知。
     */
    @Override
    public void setListener(Listener listener) {
        super.setListener(listener);
        if (mSize != STATE_WAIT) {
            notifyDataChanged();
        }
    }

    @Override
    public void setGLRoot(GLRoot glRoot) {
        super.setGLRoot(glRoot);
        if (mSize != STATE_WAIT) {
            notifyDataChanged();
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

    @Override
    protected void onRequest(int index) {
        synchronized (mRequests) {
            if (!mRequests.contains(index) && index != mDecodingIndex.get()) {
                mRequests.add(index);
                mRequests.notify();
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

    @NonNull
    @Override
    public String getImageFilename(int index) {
        return String.format(Locale.US, "page_%04d", index + 1);
    }

    @Override
    public boolean save(int index, @NonNull UniFile file) {
        Bitmap bitmap = renderPageBitmap(index);
        if (bitmap == null) {
            return false;
        }

        OutputStream os = null;
        try {
            os = file.openOutputStream();
            return bitmap.compress(Bitmap.CompressFormat.JPEG, 90, os);
        } catch (Exception e) {
            Log.e(TAG, "Failed to save PDF page " + index, e);
            return false;
        } finally {
            IOUtils.closeQuietly(os);
            bitmap.recycle();
        }
    }

    @Nullable
    @Override
    public UniFile save(int index, @NonNull UniFile dir, @NonNull String filename) {
        UniFile file = dir.createFile(filename + ".jpg");
        if (file == null) {
            return null;
        }
        return save(index, file) ? file : null;
    }

    @Override
    public void run() {
        try {
            openRenderer();
            int pageCount;
            synchronized (mRendererLock) {
                if (mRenderer == null) {
                    throw new IOException("PdfRenderer is null");
                }
                pageCount = mRenderer.getPageCount();
            }
            if (pageCount <= 0) {
                setError(R.string.pdf_no_pages);
                return;
            }

            mSize = pageCount;
            mError = null;
            notifyDataChanged();
            Log.i(TAG, "Opened PDF, pages=" + pageCount);
        } catch (SecurityException e) {
            Log.e(TAG, "PDF is encrypted", e);
            setError(R.string.pdf_encrypted);
            closeRenderer();
            return;
        } catch (Throwable e) {
            Log.e(TAG, "Failed to open PDF: " + mUri, e);
            setError(R.string.pdf_open_failed);
            closeRenderer();
            return;
        }

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

            if (index < 0 || index >= mSize) {
                mDecodingIndex.lazySet(GalleryPageView.INVALID_INDEX);
                notifyPageFailed(index, GetText.getString(R.string.error_out_of_range));
                continue;
            }

            try {
                Bitmap bitmap = renderPageBitmap(index);
                mDecodingIndex.lazySet(GalleryPageView.INVALID_INDEX);
                if (bitmap != null) {
                    Image image = Image.create(bitmap);
                    if (image != null) {
                        notifyPageSucceed(index, image);
                    } else {
                        bitmap.recycle();
                        notifyPageFailed(index, GetText.getString(R.string.pdf_render_failed));
                    }
                } else {
                    notifyPageFailed(index, GetText.getString(R.string.pdf_render_failed));
                }
            } catch (Throwable e) {
                mDecodingIndex.lazySet(GalleryPageView.INVALID_INDEX);
                Log.e(TAG, "Failed to render PDF page " + index, e);
                notifyPageFailed(index, GetText.getString(R.string.pdf_render_failed));
            }
        }

        closeRenderer();
        Log.i(TAG, "PdfGalleryProvider end");
    }

    private void setError(int resId) {
        mSize = STATE_ERROR;
        mError = GetText.getString(resId);
        notifyDataChanged();
    }

    private void openRenderer() throws IOException {
        ParcelFileDescriptor pfd;
        if (ContentResolver.SCHEME_FILE.equals(mUri.getScheme())) {
            String path = mUri.getPath();
            if (path == null) {
                throw new IOException("PDF path is null");
            }
            pfd = ParcelFileDescriptor.open(new File(path), ParcelFileDescriptor.MODE_READ_ONLY);
        } else {
            pfd = mContext.getContentResolver().openFileDescriptor(mUri, "r");
        }
        if (pfd == null) {
            throw new IOException("Can't open PDF file descriptor");
        }

        PdfRenderer renderer;
        try {
            renderer = new PdfRenderer(pfd);
        } catch (Throwable e) {
            IOUtils.closeQuietly(pfd);
            throw e;
        }

        synchronized (mRendererLock) {
            mPfd = pfd;
            mRenderer = renderer;
        }
    }

    @Nullable
    private Bitmap renderPageBitmap(int index) {
        synchronized (mRendererLock) {
            if (mRenderer == null) {
                return null;
            }

            PdfRenderer.Page page = null;
            Bitmap bitmap = null;
            try {
                page = mRenderer.openPage(index);
                final int pageWidth = page.getWidth();
                final int pageHeight = page.getHeight();
                if (pageWidth <= 0 || pageHeight <= 0) {
                    return null;
                }

                DisplayMetrics dm = mContext.getResources().getDisplayMetrics();
                int screenWidth = dm.widthPixels > 0 ? dm.widthPixels : 1080;
                int screenHeight = dm.heightPixels > 0 ? dm.heightPixels : 1920;

                float scale = Math.min(
                        Math.min((float) MAX_RENDER_WIDTH / pageWidth,
                                (float) MAX_RENDER_HEIGHT / pageHeight),
                        Math.min((float) screenWidth / pageWidth,
                                (float) screenHeight / pageHeight));
                scale = Math.max(scale, 1.0f);

                int width = Math.max(1, Math.round(pageWidth * scale));
                int height = Math.max(1, Math.round(pageHeight * scale));

                long pixels = (long) width * height;
                if (pixels > MAX_RENDER_PIXELS) {
                    double factor = Math.sqrt((double) MAX_RENDER_PIXELS / pixels);
                    width = Math.max(1, (int) (width * factor));
                    height = Math.max(1, (int) (height * factor));
                }
                if (width > MAX_RENDER_WIDTH || height > MAX_RENDER_HEIGHT) {
                    float factor = Math.min(
                            (float) MAX_RENDER_WIDTH / width,
                            (float) MAX_RENDER_HEIGHT / height);
                    width = Math.max(1, (int) (width * factor));
                    height = Math.max(1, (int) (height * factor));
                }

                bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                bitmap.eraseColor(Color.WHITE);

                Matrix matrix = new Matrix();
                matrix.setScale(width / (float) pageWidth, height / (float) pageHeight);
                page.render(bitmap, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                return bitmap;
            } catch (Throwable e) {
                Log.e(TAG, "Failed to render PDF page " + index, e);
                if (bitmap != null) {
                    bitmap.recycle();
                }
                return null;
            } finally {
                if (page != null) {
                    try {
                        page.close();
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
    }

    private void closeRenderer() {
        synchronized (mRendererLock) {
            if (mRenderer != null) {
                try {
                    mRenderer.close();
                } catch (Throwable e) {
                    Log.w(TAG, "Failed to close PdfRenderer", e);
                }
                mRenderer = null;
            }
            if (mPfd != null) {
                IOUtils.closeQuietly(mPfd);
                mPfd = null;
            }
        }
    }
}
