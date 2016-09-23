package tv.puppetmaster.tinydl;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.support.annotation.NonNull;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.support.v4.content.FileProvider;
import android.util.Log;
import android.widget.ArrayAdapter;
import android.widget.Toast;

import java.io.File;
import java.net.URL;
import java.net.URLConnection;
import java.util.ArrayList;
import java.util.List;

/**
 * A utility class for installing packages.
 */
public class PackageInstaller {
    private static final String TAG = PackageInstaller.class.getSimpleName();
    private static final boolean DEBUG = true;

    private static final File DOWNLOADS_DIRECTORY = Environment.getExternalStoragePublicDirectory(
            Environment.DIRECTORY_DOWNLOADS);
    private static final int REQUEST_READWRITE_STORAGE = 0;
    private static DownloadManager DOWNLOAD_MANAGER;

    private static PackageInstaller mPackageInstaller;

    private Activity mActivity;
    private boolean mInProgress;
    private List<DownloadCallback> callbackList;
    private BroadcastReceiver mDownloadCompleteReceiver = new BroadcastReceiver() {
        public void onReceive(Context ctxt, Intent intent) {
            Bundle extras = intent.getExtras();
            DownloadManager.Query q = new DownloadManager.Query();
            q.setFilterById(extras.getLong(DownloadManager.EXTRA_DOWNLOAD_ID));
            Cursor c = DOWNLOAD_MANAGER.query(q);

            if (c.moveToFirst()) {
                int status = c.getInt(c.getColumnIndex(DownloadManager.COLUMN_STATUS));
                if (status == DownloadManager.STATUS_SUCCESSFUL) {
                    String uriString = c.getString(c.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI));
                    if (uriString != null && uriString.endsWith(".apk")) {
                        Toast.makeText(ctxt, R.string.info_download_complete, Toast.LENGTH_LONG).show();
                        if (android.os.Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
                            for (DownloadCallback callback : callbackList) {
                                callback.onApkDownloaded(new File(Uri.parse(uriString).getPath()));
                            }
                        } else {
                            // TODO: Nougat install upon download yields "There was a problem parsing the package"
                            for (DownloadCallback callback : callbackList) {
                                callback.onApkDownloadedNougat(new File(Uri.parse(uriString).getPath()));
                            }
                        }
                    } else if (uriString != null) {
                        File fileToDelete = new File(Uri.parse(uriString).getPath());
                        boolean success = fileToDelete.delete();
                        Toast.makeText(ctxt, ctxt.getString(R.string.warning_invalid_tag) + ": " +
                                (success ? "Removed" : "Unremoved"), Toast.LENGTH_LONG).show();
                        for (DownloadCallback callback : callbackList) {
                            callback.onFileDeleted(fileToDelete,
                                    success);
                        }
                    }
                } else {
                    int reason = c.getInt(c.getColumnIndex(DownloadManager.COLUMN_REASON));
                    Toast.makeText(ctxt, ctxt.getString(R.string.warning_invalid_tag) + ": " +
                            reason, Toast.LENGTH_LONG).show();
                }
            }
            c.close();
            progressStop();
        }
    };

    private PackageInstaller() {
        callbackList = new ArrayList<>();
    }

    public static PackageInstaller initialize(Activity activity) {
        mPackageInstaller = new PackageInstaller();
        activity.registerReceiver(mPackageInstaller.mDownloadCompleteReceiver,
                new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE));

        DOWNLOAD_MANAGER = (DownloadManager) activity.getSystemService(Context.DOWNLOAD_SERVICE);
        mPackageInstaller.mActivity = activity;
        mPackageInstaller.chmod();
        return mPackageInstaller;
    }

    public void destroy() {
        mActivity.unregisterReceiver(mDownloadCompleteReceiver);
    }

    public void wget(@NonNull String downloadUrl) {
        if (!chmod()) {
            return;
        }
        progressStart();
        if (downloadUrl.isEmpty()) {
            progressStop();
            Toast.makeText(mActivity, R.string.warning_invalid_tag, Toast.LENGTH_LONG).show();
        } else {
            new DownloadFile().execute(downloadUrl);
        }
    }

    public boolean chmod() {
        int permissionCheck1 = ContextCompat.checkSelfPermission(mActivity,
                android.Manifest.permission.READ_EXTERNAL_STORAGE);
        int permissionCheck2 = ContextCompat.checkSelfPermission(mActivity,
                android.Manifest.permission.WRITE_EXTERNAL_STORAGE);
        if (permissionCheck1 != PackageManager.PERMISSION_GRANTED || permissionCheck2 != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(mActivity,
                    new String[] {
                            android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
                            android.Manifest.permission.READ_EXTERNAL_STORAGE
                    },
                    REQUEST_READWRITE_STORAGE);
            return false;
        } else {
            return true;
        }
    }

    private void exec(File file) {
        Uri uri = FileProvider.getUriForFile(mActivity, BuildConfig.APPLICATION_ID + ".provider",
                file); // Nougat style
        try {
            Intent intent = new Intent(Intent.ACTION_INSTALL_PACKAGE)
                    .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    .setDataAndType(
                            android.os.Build.VERSION.SDK_INT < Build.VERSION_CODES.N ?
                                    Uri.fromFile(file) : uri,
                            "application/vnd.android.package-archive"
                    );
            mActivity.startActivity(intent);
        } catch (Exception ex) {
            Log.e(TAG, "Start activity failed: " + uri, ex);
            Toast.makeText(mActivity, R.string.error_starting_intent, Toast.LENGTH_LONG).show();
        }
    }

    public void install(@NonNull File file) {
        exec(file);
    }

    public void addListener(DownloadCallback callback) {
        callbackList.add(callback);
    }

    public void removeListener(DownloadCallback callback) {
        callbackList.remove(callback);
    }

    private void progressStart() {
        mInProgress = true;
        for (DownloadCallback callback : callbackList) {
            callback.onProgressStarted();
        }
    }

    private void progressStop() {
        mInProgress = false;
        for (DownloadCallback callback : callbackList) {
            callback.onProgressEnded();
        }
    }

    public void deleteFile(File file) {
        new DeleteFile().execute(file);
    }

    private class DownloadFile extends AsyncTask<String, Void, Integer> {

        @Override
        protected Integer doInBackground(String... urls) {
            String downloadUri = null;
            try {
                URLConnection con = new URL(urls[0]).openConnection();
                con.getHeaderFields();
                downloadUri = con.getURL().toString();
            } catch (Exception ex) {
                Log.e("DownloadFile", "Connection error", ex);
            }
            if (downloadUri == null) {
                return R.string.error_download_failed;
            }
            final String downloadedFileName =
                    urls[0].substring(urls[0].lastIndexOf("/") + 1).trim() + ".apk";

            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(downloadUri));
            request.setTitle(mActivity.getString(R.string.app_name) + ": " + downloadedFileName);
            request.setDescription(mActivity.getString(R.string.directory) + ": " +
                    DOWNLOADS_DIRECTORY.toString());
            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS,
                    downloadedFileName);
            final DownloadManager manager =
                    (DownloadManager) mActivity.getSystemService(Context.DOWNLOAD_SERVICE);
            manager.enqueue(request);
            return -1;
        }

        @Override
        protected void onPostExecute(Integer failureMessage) {
            if (failureMessage >= 0) {
                progressStop();
                Toast.makeText(mActivity, failureMessage, Toast.LENGTH_LONG).show();
            }
        }
    }

    private class DeleteFile extends AsyncTask<File, Void, Boolean> {
        private File fileToDelete;
        @Override
        protected Boolean doInBackground(File... filenames) {
            fileToDelete = filenames[0];
            return fileToDelete.delete();
        }

        @Override
        protected void onPostExecute(Boolean deleted) {
            if (!deleted) {
                Toast.makeText(mActivity, R.string.error_deleting_file, Toast.LENGTH_LONG).show();
            }
            for (DownloadCallback callback : callbackList) {
                callback.onFileDeleted(fileToDelete, deleted);
            }
        }
    }

    public interface DownloadCallback {
        void onApkDownloaded(File downloadedApkFile);

        /**
         * Currently a bug in #SIDELOADTAG that prevents apps from being directly installed after
         * loading. This callback will be run on Nougat devices.
         */
        void onApkDownloadedNougat(File downloadedApkFile);

        void onFileDeleted(File deletedApkFile, boolean wasSuccessful);

        void onProgressStarted();

        void onProgressEnded();
    }
}
