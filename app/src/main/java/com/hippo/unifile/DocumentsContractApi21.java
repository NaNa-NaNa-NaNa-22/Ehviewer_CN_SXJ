/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hippo.unifile;

import android.annotation.TargetApi;
import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.DocumentsContract;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

@TargetApi(Build.VERSION_CODES.LOLLIPOP)
final class DocumentsContractApi21 {
    private static final String TAG = DocumentsContractApi21.class.getSimpleName();
    private static final String PATH_DOCUMENT = "document";
    private static final String PATH_TREE = "tree";

    private DocumentsContractApi21() {
    }

    public static Uri createFile(Context context, Uri self, String mimeType,
                                 String displayName) {
        try {
            return DocumentsContract.createDocument(context.getContentResolver(), self, mimeType,
                    displayName);
        } catch (Throwable e) {
            Utils.throwIfFatal(e);
            // Maybe user ejects tf card
            Log.e(TAG, "Failed to createFile: " + self, e);
            return null;
        }
    }

    public static Uri createDirectory(Context context, Uri self, String displayName) {
        return createFile(context, self, DocumentsContract.Document.MIME_TYPE_DIR, displayName);
    }

    public static Uri prepareTreeUri(Uri treeUri) {
        return DocumentsContract.buildDocumentUriUsingTree(treeUri,
                DocumentsContract.getTreeDocumentId(treeUri));
    }

    public static String getTreeDocumentPath(Uri documentUri) {
        final List<String> paths = documentUri.getPathSegments();
        if (paths.size() >= 4 && PATH_TREE.equals(paths.get(0)) && PATH_DOCUMENT.equals(paths.get(2))) {
            return paths.get(3);
        }
        throw new IllegalArgumentException("Invalid URI: " + documentUri);
    }

    public static Uri buildChildUri(Uri uri, String displayName) {
        return DocumentsContract.buildDocumentUriUsingTree(uri,
                getTreeDocumentPath(uri) + "/" + displayName);
    }

    public static Uri[] listFiles(Context context, Uri self) {
        final ContentResolver resolver = context.getContentResolver();
        final Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(self,
                DocumentsContract.getDocumentId(self));
        final ArrayList<Uri> results = new ArrayList<>();

        Cursor c = null;
        try {
            c = resolver.query(childrenUri, new String[]{
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID}, null, null, null);
            if (null != c) {
                while (c.moveToNext()) {
                    final String documentId = c.getString(0);
                    final Uri documentUri = DocumentsContract.buildDocumentUriUsingTree(self,
                            documentId);
                    results.add(documentUri);
                }
            }
        } catch (Throwable e) {
            Utils.throwIfFatal(e);
            Log.e(TAG, "Failed listFiles: " + self, e);
        } finally {
            Utils.closeQuietly(c);
        }

        return results.toArray(new Uri[results.size()]);
    }

    /**
     * 一次 query 同时取回 document id、显示名和 MIME 类型。
     *
     * <p>原来的 {@link #listFiles(Context, Uri)} 只取 id，调用方随后还要对每个子项分别
     * 调用 {@code getName()} 和 {@code isDirectory()}，在 SAF provider 上就是每文件两次
     * 额外的 ContentResolver IPC。大目录（几千张图）会因此慢到无法接受。
     *
     * @return null 表示该 provider 不支持这组 projection 或查询失败，调用方应回退到
     *         {@link #listFiles(Context, Uri)}；空数组表示目录确实为空。
     */
    public static DocumentInfo[] listFilesWithInfo(Context context, Uri self) {
        final ContentResolver resolver = context.getContentResolver();
        final Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(self,
                DocumentsContract.getDocumentId(self));
        final ArrayList<DocumentInfo> results = new ArrayList<>();

        Cursor c = null;
        try {
            c = resolver.query(childrenUri, new String[]{
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE}, null, null, null);
            if (null != c) {
                final int idIndex = c.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID);
                final int nameIndex = c.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME);
                final int typeIndex = c.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE);
                if (idIndex < 0) {
                    return null;
                }
                while (c.moveToNext()) {
                    final String documentId = c.getString(idIndex);
                    if (documentId == null) {
                        continue;
                    }
                    final Uri documentUri = DocumentsContract.buildDocumentUriUsingTree(self,
                            documentId);
                    final String displayName = nameIndex >= 0 ? c.getString(nameIndex) : null;
                    final String mimeType = typeIndex >= 0 ? c.getString(typeIndex) : null;
                    results.add(new DocumentInfo(documentUri, displayName, mimeType));
                }
            }
        } catch (Throwable e) {
            Utils.throwIfFatal(e);
            Log.e(TAG, "Failed listFilesWithInfo: " + self, e);
            return null;
        } finally {
            Utils.closeQuietly(c);
        }

        return results.toArray(new DocumentInfo[results.size()]);
    }

    static final class DocumentInfo {
        final Uri uri;
        final String displayName;
        final String mimeType;

        DocumentInfo(Uri uri, String displayName, String mimeType) {
            this.uri = uri;
            this.displayName = displayName;
            this.mimeType = mimeType;
        }
    }

    public static Uri renameTo(Context context, Uri self, String displayName) {
        try {
            return DocumentsContract.renameDocument(context.getContentResolver(), self, displayName);
        } catch (Throwable e) {
            Utils.throwIfFatal(e);
            // Maybe user ejects tf card
            Log.e(TAG, "Failed to renameTo:" + self + ", " + displayName, e);
            return null;
        }
    }
}
