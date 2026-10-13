package org.schabi.newpipe.streams.io;

import android.annotation.TargetApi;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.documentfile.provider.DocumentFile;

import com.nononsenseapps.filepicker.Utils;

import org.schabi.newpipe.MainActivity;
import org.schabi.newpipe.settings.NewPipeSettings;
import org.schabi.newpipe.util.FilePickerActivityHelper;

import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.net.URI;

import us.shandian.giga.io.FileStream;
import us.shandian.giga.io.FileStreamSAF;

public class StoredFileHelper implements Serializable {
    private static final boolean DEBUG = MainActivity.DEBUG;
    private static final String TAG = StoredFileHelper.class.getSimpleName();

    private static final long serialVersionUID = 0L;
    public static final String DEFAULT_MIME = "application/octet-stream";

    private transient DocumentFile docFile;
    public transient DocumentFile docTree;
    public transient File ioFile;
    public transient Context context;

    public String source;
    public String sourceTree;

    protected String tag;

    public String srcName;
    private String srcType;

    public StoredFileHelper() {
    }

    @NonNull
    public static StoredFileHelper fromExistingUri(@NonNull final Context context,
                                                   @NonNull final Uri path,
                                                   @Nullable final String tag) {
        return fromExistingUri(context, path, tag, null);
    }

    @NonNull
    public static StoredFileHelper fromExistingUri(@NonNull final Context context,
                                                   @NonNull final Uri path,
                                                   @Nullable final String tag,
                                                   @Nullable final String cachedName) {
        final StoredFileHelper instance = new StoredFileHelper();
        instance.context = context.getApplicationContext();
        instance.tag = tag == null ? "" : tag;
        instance.source = path.toString();

        if (path.getScheme() == null
                || path.getScheme().equalsIgnoreCase(ContentResolver.SCHEME_FILE)) {
            instance.ioFile = new File(URI.create(instance.source));
            instance.srcName = cachedName != null ? cachedName : instance.ioFile.getName();
            final File parent = instance.ioFile.getParentFile();
            if (parent != null) {
                instance.sourceTree = Uri.fromFile(parent).toString();
            }
        } else {
            instance.docFile = DocumentFile.fromSingleUri(context, path);
            if (cachedName != null) {
                instance.srcName = cachedName;
            } else {
                instance.srcName = extractNameFromUri(context, path);
            }
        }
        instance.srcType = instance.getType();
        return instance;
    }

    @Nullable
    public static String extractNameFromUri(@Nullable final Context context,
                                            @NonNull final Uri uri) {
        try {
            if (context != null && DocumentsContract.isDocumentUri(context, uri)) {
                final String docId = DocumentsContract.getDocumentId(uri);
                if (docId != null) {
                    final int lastSlash = Math.max(docId.lastIndexOf('/'), docId.lastIndexOf('%'));
                    final String rawName;
                    if (lastSlash >= 0 && lastSlash < docId.length() - 1) {
                        rawName = docId.substring(lastSlash + 1);
                    } else {
                        final int colon = docId.lastIndexOf(':');
                        if (colon >= 0 && colon < docId.length() - 1) {
                            rawName = docId.substring(colon + 1);
                        } else {
                            rawName = docId;
                        }
                    }
                    final String decoded = Uri.decode(rawName);
                    if (decoded != null && decoded.contains(".")) {
                        return decoded;
                    }
                }
            }
        } catch (final Exception ignored) {
        }

        try {
            final String lastSegment = uri.getLastPathSegment();
            if (lastSegment != null) {
                final int lastSlash = Math.max(lastSegment.lastIndexOf('/'), lastSegment.lastIndexOf('%'));
                final String rawName = (lastSlash >= 0 && lastSlash < lastSegment.length() - 1)
                        ? lastSegment.substring(lastSlash + 1) : lastSegment;
                final String decoded = Uri.decode(rawName);
                if (decoded != null && decoded.contains(".")) {
                    return decoded;
                }
            }
        } catch (final Exception ignored) {
        }

        return null;
    }

    public StoredFileHelper(final Context context, final Uri uri, final String mime) {
        if (FilePickerActivityHelper.isOwnFileUri(context, uri)) {
            ioFile = Utils.getFileForUri(uri);
            source = Uri.fromFile(ioFile).toString();
        } else {
            docFile = DocumentFile.fromSingleUri(context, uri);
            source = uri.toString();
        }

        this.context = context;
        this.srcType = mime;
    }

    public StoredFileHelper(@Nullable final Uri parent, final String filename, final String mime,
                            final String tag) {
        this.source = null; // this instance will be "invalid" see invalidate()/isInvalid() methods

        this.srcName = filename;
        this.srcType = mime == null ? DEFAULT_MIME : mime;
        if (parent != null) {
            this.sourceTree = parent.toString();
        }

        this.tag = tag;
    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    StoredFileHelper(@Nullable final Context context, final DocumentFile tree,
                     final String filename, final String mime, final boolean safe)
            throws IOException {
        this.docTree = tree;
        this.context = context;

        final DocumentFile res;

        if (safe) {
            // no conflicts (the filename is not in use)
            res = this.docTree.createFile(mime, filename);
            if (res == null) {
                throw new IOException("Cannot create the file");
            }
        } else {
            res = createSAF(context, mime, filename);
        }

        this.docFile = res;

        this.source = docFile.getUri().toString();
        this.sourceTree = docTree.getUri().toString();

        this.srcName = this.docFile.getName();
        this.srcType = this.docFile.getType();
    }

    StoredFileHelper(final File location, final String filename, final String mime)
            throws IOException {
        this.ioFile = new File(location, filename);

        if (this.ioFile.exists()) {
            if (!this.ioFile.isFile() && !this.ioFile.delete()) {
                throw new IOException("The filename is already in use by non-file entity "
                        + "and cannot overwrite it");
            }
        } else {
            if (!this.ioFile.createNewFile()) {
                throw new IOException("Cannot create the file");
            }
        }

        this.source = Uri.fromFile(this.ioFile).toString();
        this.sourceTree = Uri.fromFile(location).toString();

        this.srcName = ioFile.getName();
        this.srcType = mime;
    }

    @TargetApi(Build.VERSION_CODES.KITKAT)
    public StoredFileHelper(final Context context, @Nullable final Uri parent,
                            @NonNull final Uri path, final String tag) throws IOException {
        this.tag = tag;
        this.source = path.toString();

        if (path.getScheme() == null
                || path.getScheme().equalsIgnoreCase(ContentResolver.SCHEME_FILE)) {
            this.ioFile = new File(URI.create(this.source));
        } else {
            final DocumentFile file = DocumentFile.fromSingleUri(context, path);

            if (file == null) {
                throw new RuntimeException("SAF not available");
            }

            this.context = context;

            if (file.getName() == null) {
                this.source = null;
                return;
            } else {
                this.docFile = file;
                takePermissionSAF();
            }
        }

        if (parent != null) {
            if (!ContentResolver.SCHEME_FILE.equals(parent.getScheme())) {
                this.docTree = DocumentFile.fromTreeUri(context, parent);
            }

            this.sourceTree = parent.toString();
        }

        if (sourceTree == null && source != null) {
            if (ioFile != null) {
                final File parentDir = ioFile.getParentFile();
                if (parentDir != null) {
                    this.sourceTree = Uri.fromFile(parentDir).toString();
                }
            }
        }

        this.srcName = getName();
        this.srcType = getType();
    }


    public static StoredFileHelper deserialize(@NonNull final StoredFileHelper storage,
                                               final Context context) throws IOException {
        final Uri treeUri = storage.sourceTree == null ? null : Uri.parse(storage.sourceTree);

        if (storage.isInvalid()) {
            return new StoredFileHelper(treeUri, storage.srcName, storage.srcType, storage.tag);
        }

        final StoredFileHelper instance = new StoredFileHelper(context, treeUri,
                Uri.parse(storage.source), storage.tag);

        // under SAF, if the target document is deleted, conserve the filename and mime
        if (instance.srcName == null) {
            instance.srcName = storage.srcName;
        }
        if (instance.srcType == null) {
            instance.srcType = storage.srcType;
        }

        return instance;
    }

    public SharpStream getStream() throws IOException {
        assertValid();

        if (docFile == null) {
            return new FileStream(ioFile);
        } else {
            return new FileStreamSAF(context.getContentResolver(), docFile.getUri());
        }
    }

    /**
     * Indicates whether it's using the {@code java.io} API.
     *
     * @return {@code true} for Java I/O API, otherwise, {@code false} for Storage Access Framework
     */
    public boolean isDirect() {
        assertValid();

        return docFile == null;
    }

    public boolean isInvalid() {
        return source == null;
    }

    public Uri getUri() {
        assertValid();

        return docFile == null ? Uri.fromFile(ioFile) : docFile.getUri();
    }

    public Uri getParentUri() {
        assertValid();

        return sourceTree == null ? null : Uri.parse(sourceTree);
    }

    public void truncate() throws IOException {
        assertValid();

        try (SharpStream fs = getStream()) {
            fs.setLength(0);
        }
    }

    public boolean delete() {
        if (source == null) {
            return true;
        }
        if (docFile == null) {
            return ioFile.delete();
        }

        final boolean res = docFile.delete();

        try {
            final int flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
            context.getContentResolver().releasePersistableUriPermission(docFile.getUri(), flags);
        } catch (final Exception ex) {
            // nothing to do
        }

        return res;
    }

    public long length() {
        assertValid();

        return docFile == null ? ioFile.length() : docFile.length();
    }

    public boolean canWrite() {
        if (source == null) {
            return false;
        }
        return docFile == null ? ioFile.canWrite() : docFile.canWrite();
    }

    public String getName() {
        if (srcName != null) {
            return srcName;
        }
        if (source == null) {
            return null;
        } else if (docFile == null) {
            srcName = ioFile.getName();
            return srcName;
        }

        final String name = docFile.getName();
        if (name != null) {
            srcName = name;
            return name;
        }
        return null;
    }

    public String getType() {
        if (srcType != null) {
            return srcType;
        }
        if (source == null || docFile == null) {
            return null;
        }

        final String type = docFile.getType();
        if (type != null) {
            srcType = type;
            return type;
        }
        return null;
    }

    public String getTag() {
        return tag;
    }

    public boolean existsAsFile() {
        if (source == null || (docFile == null && ioFile == null)) {
            if (DEBUG) {
                Log.d(TAG, "existsAsFile called but something is null: source = ["
                        + (source == null ? "null => storage is invalid" : source)
                        + "], docFile = [" + (docFile == null ? "null" : docFile)
                        + "], ioFile = [" + (ioFile == null ? "null" : ioFile) + "]");
            }
            return false;
        }

        // WARNING: DocumentFile.exists() and DocumentFile.isFile() methods are slow
        // docFile.isVirtual() means it is non-physical?
        return docFile == null
                ? (ioFile.exists() && ioFile.isFile())
                : (docFile.exists() && docFile.isFile());
    }

    public boolean create() {
        assertValid();
        final boolean result;

        if (docFile == null) {
            try {
                result = ioFile.createNewFile();
            } catch (final IOException e) {
                return false;
            }
        } else if (docTree == null) {
            result = false;
        } else {
            if (!docTree.canRead() || !docTree.canWrite()) {
                return false;
            }
            try {
                docFile = createSAF(context, srcType, srcName);
                if (docFile.getName() == null) {
                    return false;
                }
                result = true;
            } catch (final IOException e) {
                return false;
            }
        }

        if (result) {
            source = (docFile == null ? Uri.fromFile(ioFile) : docFile.getUri()).toString();
            srcName = getName();
            srcType = getType();
        }

        return result;
    }

    public void invalidate() {
        if (source == null) {
            return;
        }

        srcName = getName();
        srcType = getType();

        source = null;

        docTree = null;
        docFile = null;
        ioFile = null;
        context = null;
    }

    public boolean equals(final StoredFileHelper storage) {
        if (this == storage) {
            return true;
        }

        if (storage == null) {
            return false;
        }

        // note: do not compare tags, files can have the same parent folder
        //if (stringMismatch(this.tag, storage.tag)) return false;

        if (this.sourceTree != null && storage.sourceTree != null
                && stringMismatch(getLowerCase(this.sourceTree), getLowerCase(storage.sourceTree))) {
            return false;
        }

        if (this.isInvalid() || storage.isInvalid()) {
            final String name1 = this.getName();
            final String name2 = storage.getName();
            if (name1 == null || name2 == null) {
                return false;
            }

            return name1.equalsIgnoreCase(name2);
        }

        if (this.isDirect() != storage.isDirect()) {
            return false;
        }

        if (this.isDirect()) {
            return this.ioFile.getPath().equalsIgnoreCase(storage.ioFile.getPath());
        }

        if (this.docFile != null && storage.docFile != null) {
            if (this.docFile.getUri().equals(storage.docFile.getUri())) {
                return true;
            }
            try {
                if (DocumentsContract.getDocumentId(this.docFile.getUri())
                        .equalsIgnoreCase(DocumentsContract.getDocumentId(storage.docFile.getUri()))) {
                    return true;
                }
            } catch (final Exception ignored) {
                if (this.docFile.getUri().toString().equalsIgnoreCase(storage.docFile.getUri().toString())) {
                    return true;
                }
            }

            final String name1 = this.getName();
            final String name2 = storage.getName();
            if (name1 != null && name2 != null && name1.equalsIgnoreCase(name2)) {
                return true;
            }
        }

        return false;
    }

    @NonNull
    @Override
    public String toString() {
        if (source == null) {
            return "[Invalid state] name=" + srcName + "  type=" + srcType + "  tag=" + tag;
        } else {
            return "sourceFile=" + source + "  treeSource=" + (sourceTree == null ? "" : sourceTree)
                    + "  tag=" + tag;
        }
    }


    private void assertValid() {
        if (source == null) {
            throw new IllegalStateException("In invalid state");
        }
    }

    private void takePermissionSAF() throws IOException {
        try {
            context.getContentResolver().takePersistableUriPermission(docFile.getUri(),
                    StoredDirectoryHelper.PERMISSION_FLAGS);
        } catch (final Exception e) {
            if (docFile.getName() == null) {
                throw new IOException(e);
            }
        }
    }

    @NonNull
    private DocumentFile createSAF(@Nullable final Context ctx, final String mime,
                                   final String filename) throws IOException {
        DocumentFile res = StoredDirectoryHelper.findFileSAFHelper(ctx, docTree, filename);
        if(res != null && !res.getName().equals(filename)){
            res = null;
        }

        if (res != null && res.exists() && res.isDirectory()) {
            if (!res.delete()) {
                throw new IOException("Directory with the same name found but cannot delete");
            }
            res = null;
        }

        if (res == null) {
            res = this.docTree.createFile(srcType == null ? DEFAULT_MIME : mime, filename);
            if (res == null) {
                throw new IOException("Cannot create the file");
            }
        }

        return res;
    }

    private String getLowerCase(final String str) {
        return str == null ? null : str.toLowerCase();
    }

    private boolean stringMismatch(final String str1, final String str2) {
        if (str1 == null && str2 == null) {
            return false;
        }
        if ((str1 == null) != (str2 == null)) {
            return true;
        }

        return !str1.equals(str2);
    }

    public static Intent getPicker(@NonNull final Context ctx,
                                   @NonNull final String mimeType) {
        if (NewPipeSettings.useStorageAccessFramework(ctx)) {
            return new Intent(Intent.ACTION_OPEN_DOCUMENT)
                    .putExtra("android.content.extra.SHOW_ADVANCED", true)
                    .setType(mimeType)
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                            | StoredDirectoryHelper.PERMISSION_FLAGS);
        } else {
            return new Intent(ctx, FilePickerActivityHelper.class)
                    .putExtra(FilePickerActivityHelper.EXTRA_ALLOW_MULTIPLE, false)
                    .putExtra(FilePickerActivityHelper.EXTRA_ALLOW_CREATE_DIR, true)
                    .putExtra(FilePickerActivityHelper.EXTRA_SINGLE_CLICK, true)
                    .putExtra(FilePickerActivityHelper.EXTRA_MODE,
                            FilePickerActivityHelper.MODE_FILE);
        }
    }

    public static Intent getPicker(@NonNull final Context ctx,
                                   @NonNull final String mimeType,
                                   @Nullable final Uri initialPath) {
        return applyInitialPathToPickerIntent(ctx, getPicker(ctx, mimeType), initialPath, null);
    }

    public static Intent getNewPicker(@NonNull final Context ctx,
                                      @Nullable final String filename,
                                      @NonNull final String mimeType,
                                      @Nullable final Uri initialPath) {
        final Intent i;
        if (NewPipeSettings.useStorageAccessFramework(ctx)) {
            i = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                    .putExtra("android.content.extra.SHOW_ADVANCED", true)
                    .setType(mimeType)
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                            | StoredDirectoryHelper.PERMISSION_FLAGS);
            if (filename != null) {
                i.putExtra(Intent.EXTRA_TITLE, filename);
            }
        } else {
            i = new Intent(ctx, FilePickerActivityHelper.class)
                    .putExtra(FilePickerActivityHelper.EXTRA_ALLOW_MULTIPLE, false)
                    .putExtra(FilePickerActivityHelper.EXTRA_ALLOW_CREATE_DIR, true)
                    .putExtra(FilePickerActivityHelper.EXTRA_ALLOW_EXISTING_FILE, true)
                    .putExtra(FilePickerActivityHelper.EXTRA_MODE,
                            FilePickerActivityHelper.MODE_NEW_FILE);
        }
        return applyInitialPathToPickerIntent(ctx, i, initialPath, filename);
    }

    private static Intent applyInitialPathToPickerIntent(@NonNull final Context ctx,
                                                         @NonNull final Intent intent,
                                                         @Nullable final Uri initialPath,
                                                         @Nullable final String filename) {

        if (NewPipeSettings.useStorageAccessFramework(ctx)) {
            if (initialPath == null) {
                return intent; // nothing to do, no initial path provided
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                return intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, initialPath);
            } else {
                return intent; // can't set initial path on API < 26
            }

        } else {
            if (initialPath == null && filename == null) {
                return intent; // nothing to do, no initial path and no file name provided
            }

            File file;
            if (initialPath == null) {
                // The only way to set the previewed filename in non-SAF FilePicker is to set a
                // starting path ending with that filename. So when the initialPath is null but
                // filename isn't just default to the external storage directory.
                file = Environment.getExternalStorageDirectory();
            } else {
                try {
                    file = Utils.getFileForUri(initialPath);
                } catch (final Throwable ignored) {
                    // getFileForUri() can't decode paths to 'storage', fallback to this
                    file = new File(initialPath.toString());
                }
            }

            // remove any filename at the end of the path (get the parent directory in that case)
            if (!file.exists() || !file.isDirectory()) {
                file = file.getParentFile();
                if (file == null || !file.exists()) {
                    // default to the external storage directory in case of an invalid path
                    file = Environment.getExternalStorageDirectory();
                }
                // else: file is surely a directory
            }

            if (filename != null) {
                // append a filename so that the non-SAF FilePicker shows it as the preview
                file = new File(file, filename);
            }

            return intent
                    .putExtra(FilePickerActivityHelper.EXTRA_START_PATH, file.getAbsolutePath());
        }
    }

}
