/*
 * Copyright (C) 2024 The LeafOS Project
 *               2024 Kusuma
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 */

package com.android.server.utils;

import android.content.Context;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Environment;
import android.os.SELinux;
import android.os.SystemProperties;
import android.util.Log;

import com.android.server.SystemService;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class PropImitationService extends SystemService {
    private static final String TAG = PropImitationService.class.getSimpleName();
    private static final String PROPS_API = 
            "https://gitea.com/ArrowOS-T/android_vendor_certification/raw/branch/main/props.json";
    private static final String KEYS_API = 
            "https://gitea.com/ArrowOS-T/android_vendor_certification/raw/branch/main/keys.xml";

    private static final String PROPS_FILE = "props.json";
    private static final String KEYS_FILE = "keys.xml";

    private static final long INITIAL_DELAY = 0;
    private static final long INTERVAL = 5;

    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);
    private static final boolean USE_PROPS_SPOOF =
            SystemProperties.getBoolean("persist.sys.extra.use_props", true);

    private final Context mContext;
    private final File mPropsFile;
    private final File mKeysFile;
    private final ScheduledExecutorService mScheduler;

    public PropImitationService(Context context) {
        super(context);
        mContext = context;
        mPropsFile = new File(Environment.getDataSystemDirectory(), PROPS_FILE);
        mKeysFile = new File(Environment.getDataSystemDirectory(), KEYS_FILE);
        mScheduler = Executors.newSingleThreadScheduledExecutor();
    }

    @Override
    public void onStart() {}

    @Override
    public void onBootPhase(int phase) {
        if (USE_PROPS_SPOOF && isAppInstalled("com.google.android.gms")
                && (isAppInstalled("com.google.android.syncadapters.calendar")
                || isAppInstalled("com.google.android.syncadapters.contacts"))
                && phase == PHASE_BOOT_COMPLETED) {
            Log.i(TAG, "Scheduling the service");
            mScheduler.scheduleAtFixedRate(
                    new FetchGmsCertifiedFiles(), INITIAL_DELAY, INTERVAL, TimeUnit.MINUTES);
        }
    }

    private String readFromFile(File file) {
        StringBuilder content = new StringBuilder();

        if (file.exists()) {
            try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
                String line;

                while ((line = reader.readLine()) != null) {
                    content.append(line).append(System.lineSeparator());
                }
            } catch (IOException e) {
                Log.e(TAG, "Error reading from file", e);
            }
        }
        return content.toString();
    }

    private void writeToFile(File file, String data) {
        try (FileWriter writer = new FileWriter(file)) {
            writer.write(data);
            // Set -rw-r--r-- (644) permission to make it readable by others.
            file.setReadable(true, false);
            // Set the SELinux context of the file to "extra_spoof_data_file".
            String selinuxContext = "u:object_r:extra_spoof_data_file:s0";
            SELinux.setFileContext(file.getAbsolutePath(), selinuxContext);
        } catch (IOException e) {
            Log.e(TAG, "Error writing to file", e);
        }
    }

    private String fetchFile(URL url) {
        try {
            HttpURLConnection urlConnection = (HttpURLConnection) url.openConnection();

            try {
                urlConnection.setConnectTimeout(10000);
                urlConnection.setReadTimeout(10000);
                int responseCode = urlConnection.getResponseCode();

                if (responseCode == HttpURLConnection.HTTP_OK) {
                    try (BufferedReader reader =
                            new BufferedReader(new InputStreamReader(
                            urlConnection.getInputStream()))) {
                        StringBuilder response = new StringBuilder();
                        String line;

                        while ((line = reader.readLine()) != null) {
                            response.append(line).append(System.lineSeparator());
                        }

                        return response.toString();
                    }
                } else {
                    Log.e(TAG, "API request failed with response code: " + responseCode);
                    return null;
                }
            } finally {
                urlConnection.disconnect();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error making an API request", e);
            return null;
        }
    }

    private boolean isInternetConnected() {
        ConnectivityManager cm =
                (ConnectivityManager) mContext.getSystemService(Context.CONNECTIVITY_SERVICE);
        Network nw = cm.getActiveNetwork();
        if (nw == null) return false;
        NetworkCapabilities actNw = cm.getNetworkCapabilities(nw);
        return actNw != null
                && (actNw.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                        || actNw.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                        || actNw.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                        || actNw.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH));
    }

    private boolean isAppInstalled(String packageName) {
        PackageManager pm = mContext.getPackageManager();
        try {
            pm.getPackageInfo(packageName, PackageManager.GET_ACTIVITIES);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            Log.i(TAG, packageName + " is not installed");
            return false;
        }
    }

    private void dlog(String message) {
        if (DEBUG) Log.d(TAG, message);
    }

    private class FetchGmsCertifiedFiles implements Runnable {
        @Override
        public void run() {
            try {
                dlog("FetchGmsCertifiedFiles started");

                if (!isInternetConnected()) {
                    Log.e(TAG, "Internet unavailable");
                    return;
                }

                URL propsUrl = new URI(PROPS_API).toURL();
                String savedProps = readFromFile(mPropsFile);
                String props = fetchFile(propsUrl);

                if (props != null && !savedProps.equals(props)) {
                    dlog("Found new props");
                    writeToFile(mPropsFile, props);
                    dlog("Fetching props completed");
                } else {
                    dlog("No change in props");
                }

                URL keysUrl = new URI(KEYS_API).toURL();
                String savedKeys = readFromFile(mKeysFile);
                String keys = fetchFile(keysUrl);

                if (keys != null && !savedKeys.equals(keys)) {
                    dlog("Found new keys");
                    writeToFile(mKeysFile, keys);
                    dlog("Fetching keys completed");
                } else {
                    dlog("No change in keys");
                }
            } catch (Exception e) {
                Log.e(TAG, "Error in FetchGmsCertifiedFiles", e);
            }
        }
    }
}
