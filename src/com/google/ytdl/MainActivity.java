/*
 * Copyright (c) 2013 Google Inc.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package com.google.ytdl;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import android.accounts.AccountManager;
import android.app.Activity;
import android.app.Dialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.preference.PreferenceManager;
import android.provider.MediaStore;
import android.support.v4.content.LocalBroadcastManager;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListAdapter;
import android.widget.ListView;
import android.widget.TextView;

import com.google.android.gms.common.GooglePlayServicesUtil;
import com.google.android.gms.common.Scopes;
import com.google.api.client.extensions.android.http.AndroidHttp;
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential;
import com.google.api.client.googleapis.extensions.android.gms.auth.GooglePlayServicesAvailabilityIOException;
import com.google.api.client.googleapis.extensions.android.gms.auth.UserRecoverableAuthIOException;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.client.util.ExponentialBackOff;
import com.google.api.services.plus.Plus;
import com.google.api.services.plus.model.Person;
import com.google.api.services.youtube.YouTube;
import com.google.api.services.youtube.YouTubeScopes;
import com.google.api.services.youtube.model.ChannelListResponse;
import com.google.api.services.youtube.model.PlaylistItem;
import com.google.api.services.youtube.model.PlaylistItemListResponse;
import com.google.api.services.youtube.model.Video;
import com.google.api.services.youtube.model.VideoListResponse;
import com.google.ytdl.util.ImageFetcher;
import com.google.ytdl.util.Utils;
import com.google.ytdl.util.VideoData;

/**
 * @author Ibrahim Ulukaya <ulukaya@google.com>
 *         <p/>
 *         Main activity class which handles authorization and intents.
 */
public class MainActivity extends Activity implements UploadsListFragment.Callbacks,
        DirectFragment.Callbacks {
    private static final int REQUEST_GOOGLE_PLAY_SERVICES = 0;
    private static final int REQUEST_GMS_ERROR_DIALOG = 1;
    private static final int REQUEST_ACCOUNT_PICKER = 2;
    private static final int REQUEST_AUTHORIZATION = 3;
    private static final int RESULT_PICK_IMAGE_CROP = 4;
    private static final int RESULT_VIDEO_CAP = 5;
    static final String INVALIDATE_TOKEN_INTENT = "com.google.ytdl.invalidate";
    public static final String ACCOUNT_KEY = "accountName";
    public static final String YOUTUBE_WATCH_URL_PREFIX = "http://www.youtube.com/watch?v=";

    private ImageFetcher mImageFetcher;

    private InvalidateTokenReceiver invalidateTokenReceiver;

    private String mChosenAccountName;
    private Uri mFileURI = null;
    private Handler mHandler = new Handler();

    private VideoData mVideoData;

    private Button mButton;

    private UploadsListFragment mUploadsListFragment;
    private DirectFragment mDirectFragment;
    
    GoogleAccountCredential credential;
    final HttpTransport transport = AndroidHttp.newCompatibleTransport();
    final JsonFactory jsonFactory = new GsonFactory();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        getWindow().requestFeature(Window.FEATURE_INDETERMINATE_PROGRESS);
        super.onCreate(savedInstanceState);

        // Check to see if the proper keys and playlist IDs have been set up
        if (!isCorrectlyConfigured()) { // TODO implement me
            setContentView(R.layout.developer_setup_required);
            showMissingConfigurations();
        } else {
            setContentView(R.layout.activity_main);

            ensureFetcher();
            mButton = (Button) findViewById(R.id.upload_button);
            mButton.setEnabled(false);
            
            credential =
                    GoogleAccountCredential.usingOAuth2(getApplicationContext(), Arrays.asList(Scopes.PLUS_PROFILE, YouTubeScopes.YOUTUBE));
            // set exponential backoff policy
            credential.setBackOff(new ExponentialBackOff());
            
            SharedPreferences settings = getPreferences(Context.MODE_PRIVATE);
            credential.setSelectedAccountName(settings.getString(ACCOUNT_KEY, null));            
            //TODO check to remove
            if (savedInstanceState != null) {
                mChosenAccountName = savedInstanceState.getString(ACCOUNT_KEY);
            } else {
                loadAccount();
            }
            
            credential.setSelectedAccountName(mChosenAccountName);

            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    loadData();
                }
            });

            mUploadsListFragment =
                    (UploadsListFragment) getFragmentManager().findFragmentById(R.id.list_fragment);
            mDirectFragment = (DirectFragment) getFragmentManager().findFragmentById(R.id.direct_fragment);
        }
    }

    /**
     * This method checks various internal states to figure out at startup time whether certain elements
     * have been configured correctly by the developer. Checks that:
     * <ul>
     * <li>the API key has been configured</li>
     * <li>the playlist ID has been configured</li>
     * </ul>
     *
     * @return true if the application is correctly configured for use, false if not
     */
    private boolean isCorrectlyConfigured() {
        // This isn't going to internationalize well, but we only really need this for the sample app.
        // Real applications will remove this section of code and ensure that all of these values are configured.
        if(Auth.KEY.startsWith("Replace")) {
            return false;
        }
        if(Constants.UPLOAD_PLAYLIST.startsWith("Replace")) {
            return false;
        }
        return true;
    }

    /**
     * Private class representing a missing configuration and what the developer can do to fix the issue.
     */
    private class MissingConfig {

        public final String title;
        public final String body;

        public MissingConfig(String title, String body) {
            this.title = title;
            this.body = body;
        }
    }

    /**
     * This method renders the ListView explaining what the configurations the developer of this application
     * has to complete. Typically, these are static variables defined in {@link Auth} and {@link Constants}.
     */
    private void showMissingConfigurations() {
        List<MissingConfig> missingConfigs = new ArrayList<MissingConfig>();

        // Make sure an API key is registered
        if(Auth.KEY.startsWith("Replace")) {
            missingConfigs.add(new MissingConfig("API key not configured", "KEY constant in Auth.java must be configured with your Simple API key from the Google API Console"));
        }

        // Make sure a playlist ID is registered
        if(Constants.UPLOAD_PLAYLIST.startsWith("Replace")) {
            missingConfigs.add(new MissingConfig("Playlist ID not configured", "UPLOAD_PLAYLIST constant in Constants.java must be configured with a Playlist ID to submit to. (The playlist ID typically has a prexix of PL)"));
        }


        // Renders a simple_list_item_2, which consists of a title and a body element
        ListAdapter adapter = new ArrayAdapter<MissingConfig>(this, android.R.layout.simple_list_item_2, missingConfigs) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View row;
                if(convertView == null){
                    LayoutInflater inflater = (LayoutInflater) getApplicationContext().getSystemService(Context.LAYOUT_INFLATER_SERVICE);
                    row = inflater.inflate(android.R.layout.simple_list_item_2, null);
                }else{
                    row = convertView;
                }

                TextView titleView = (TextView) row.findViewById(android.R.id.text1);
                TextView bodyView = (TextView) row.findViewById(android.R.id.text2);
                MissingConfig config = getItem(position);
                titleView.setText(config.title);
                bodyView.setText(config.body);
                return row;
            }
        };

        // Wire the data adapter up to the view
        ListView missingConfigList = (ListView) findViewById(R.id.missing_config_list);
        missingConfigList.setAdapter(adapter);
    }

    @Override
    public void onResume() {
        super.onResume();
        if (invalidateTokenReceiver == null) invalidateTokenReceiver = new InvalidateTokenReceiver();
        IntentFilter intentFilter = new IntentFilter(INVALIDATE_TOKEN_INTENT);
        LocalBroadcastManager.getInstance(this).registerReceiver(invalidateTokenReceiver, intentFilter);
        if (checkGooglePlayServicesAvailable()) {
            haveGooglePlayServices();
          }
    }

    private void ensureFetcher() {
        if (mImageFetcher == null) {
            mImageFetcher = new ImageFetcher(this, 512, 512);
            mImageFetcher.addImageCache(getFragmentManager(),
                    new com.google.ytdl.util.ImageCache.ImageCacheParams(this, "cache"));
        }
    }

    private void loadAccount() {
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(this);
        mChosenAccountName = sp.getString(ACCOUNT_KEY, null);
        invalidateOptionsMenu();
    }

    private void saveAccount() {
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(this);
        sp.edit().putString(ACCOUNT_KEY, mChosenAccountName).commit();
    }

    private void loadData() {
        if (mChosenAccountName == null) {
            return;
        }

        loadProfile();
        loadUploadedVideos();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (invalidateTokenReceiver != null) {
            LocalBroadcastManager.getInstance(this).unregisterReceiver(invalidateTokenReceiver);
        }
        if (isFinishing()) {
            mHandler.removeCallbacksAndMessages(null);
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        super.onCreateOptionsMenu(menu);
        getMenuInflater().inflate(R.menu.activity_main, menu);
        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
        case R.id.menu_refresh:
          loadData();
          break;
        case R.id.menu_accounts:
          chooseAccount();
          return true;
      }
      return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        switch (requestCode) {
        case REQUEST_GMS_ERROR_DIALOG:
            break;
        case RESULT_PICK_IMAGE_CROP:
            if (resultCode == RESULT_OK) {
                mFileURI = data.getData();
                mVideoData = null; // TODO
            }
            break;

        case RESULT_VIDEO_CAP:
            if (resultCode == RESULT_OK) {
                mFileURI = data.getData();
                mVideoData = null; // TODO
            }
            break;
          case REQUEST_GOOGLE_PLAY_SERVICES:
            if (resultCode == Activity.RESULT_OK) {
              haveGooglePlayServices();
            } else {
              checkGooglePlayServicesAvailable();
            }
            break;
          case REQUEST_AUTHORIZATION:
            if (resultCode == Activity.RESULT_OK) {
                // load data
                loadData();
            } else {
              chooseAccount();
            }
            break;
          case REQUEST_ACCOUNT_PICKER:
            if (resultCode == Activity.RESULT_OK && data != null && data.getExtras() != null) {
              String accountName = data.getExtras().getString(AccountManager.KEY_ACCOUNT_NAME);
              if (accountName != null) {
                mChosenAccountName = accountName;
                credential.setSelectedAccountName(accountName);
                SharedPreferences settings = getPreferences(Context.MODE_PRIVATE);
                SharedPreferences.Editor editor = settings.edit();
                editor.putString(ACCOUNT_KEY, accountName);
                editor.commit();
                // load data
                loadData();
              }
            }
            break;
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString(ACCOUNT_KEY, mChosenAccountName);
        //outState.putString(TOKEN_KEY, mToken);
    }

    private void loadProfile() {
        new AsyncTask<Void, Void, Person>() {
            @Override
            protected Person doInBackground(Void... voids) {
//                credential =
//                        GoogleAccountCredential.usingOAuth2(getApplicationContext(), Arrays.asList(Scopes.PLUS_PROFILE, YouTubeScopes.YOUTUBE));
//                credential.setBackOff(new ExponentialBackOff());
//                
//                SharedPreferences settings = getPreferences(Context.MODE_PRIVATE);
//                credential.setSelectedAccountName(settings.getString(ACCOUNT_KEY, null));   
                
                Plus plus =
                        new Plus.Builder(transport, jsonFactory, credential).setApplicationName(
                                Constants.APP_NAME).build();

                try {
                    return plus.people().get("me").execute();
                } catch (final GooglePlayServicesAvailabilityIOException availabilityException) {
                    showGooglePlayServicesAvailabilityErrorDialog(
                        availabilityException.getConnectionStatusCode());
                  } catch (UserRecoverableAuthIOException userRecoverableException) {
                    startActivityForResult(
                        userRecoverableException.getIntent(), REQUEST_AUTHORIZATION);
                  } catch (IOException e) {
                    Utils.logAndShow(MainActivity.this, Constants.APP_NAME, e);
                  }
                  return null;
                
            }

            @Override
            protected void onPostExecute(Person me) {
                mUploadsListFragment.setProfileInfo(me);
            }

        }.execute((Void) null);
    }

    private void loadUploadedVideos() {
        if (mChosenAccountName == null) {
            return;
        }

        setProgressBarIndeterminateVisibility(true);
        new AsyncTask<Void, Void, List<VideoData>>() {
            @Override
            protected List<VideoData> doInBackground(Void... voids) {

                YouTube youtube =
                        new YouTube.Builder(transport, jsonFactory, credential).setApplicationName(
                                Constants.APP_NAME).build();

                try {
                    /*
                     * Now that the user is authenticated, the app makes a channels list request to get the
                     * authenticated user's channel. Returned with that data is the playlist id for the uploaded
                     * videos. https://developers.google.com/youtube/v3/docs/channels/list
                     */
                    ChannelListResponse clr = youtube.channels().list("contentDetails").setMine(true).execute();
                    
                    // Get the user's uploads playlist's id from channel list response
                    String uploadsPlaylistId =
                            clr.getItems().get(0).getContentDetails().getRelatedPlaylists().getUploads();

                    List<VideoData> videos = new ArrayList<VideoData>();
                    
                    // Get videos from user's upload playlist with a playlist items list request
                    PlaylistItemListResponse pilr =
                            youtube.playlistItems().list("id,contentDetails").setPlaylistId(uploadsPlaylistId)
                                    .setMaxResults(20l).execute();
                    List<String> videoIds = new ArrayList<String>();
                    
                    // Iterate over playlist item list response to get uploaded videos' ids.
                    for (PlaylistItem item : pilr.getItems()) {
                        videoIds.add(item.getContentDetails().getVideoId());
                    }
                    
                    // Get details of uploaded videos with a videos list request.
                    VideoListResponse vlr =
                            youtube.videos().list("id,snippet,status").setId(TextUtils.join(",", videoIds)).execute();

                    // Add only the public videos to the local videos list.
                    for (Video video : vlr.getItems()) {
                        if ("public".equals(video.getStatus().getPrivacyStatus())) {
                            VideoData videoData = new VideoData();
                            videoData.setVideo(video);
                            videos.add(videoData);
                        }
                    }
                    
                    //Sort videos by title
                    Collections.sort(videos, new Comparator<VideoData>() {
                        @Override
                        public int compare(VideoData videoData, VideoData videoData2) {
                            return videoData.getTitle().compareTo(videoData2.getTitle());
                        }
                    });

                    return videos;

                } catch (final GooglePlayServicesAvailabilityIOException availabilityException) {
                    showGooglePlayServicesAvailabilityErrorDialog(
                        availabilityException.getConnectionStatusCode());
                  } catch (UserRecoverableAuthIOException userRecoverableException) {
                    startActivityForResult(
                        userRecoverableException.getIntent(), REQUEST_AUTHORIZATION);
                  } catch (IOException e) {
                    Utils.logAndShow(MainActivity.this, Constants.APP_NAME, e);
                  }
                  return null;
            }

            @Override
            protected void onPostExecute(List<VideoData> videos) {
                setProgressBarIndeterminateVisibility(false);

                if (videos == null) {
                    return;
                }

                mUploadsListFragment.setVideos(videos);
            }

        }.execute((Void) null);
    }

    @Override
    public void onBackPressed() {
        // if (mDirectFragment.popPlayerFromBackStack()) {
        // super.onBackPressed();
        // }
    }

    @Override
    public ImageFetcher onGetImageFetcher() {
        ensureFetcher();
        return mImageFetcher;
    }

    @Override
    public void onVideoSelected(VideoData video) {
        mVideoData = video;
        mButton.setEnabled(true);
        mDirectFragment.panToVideo(video);
    }

    public void pickFile(View view) {
        Intent intent = new Intent(Intent.ACTION_PICK); // TODO
        // ACTION_GET_CONTENT
        intent.setType("video/*");
        startActivityForResult(intent, RESULT_PICK_IMAGE_CROP);
        mButton.setEnabled(true);
    }

    public void recordVideo(View view) {
        Intent intent = new Intent(MediaStore.ACTION_VIDEO_CAPTURE); // TODO
        // ACTION_GET_CONTENT
        startActivityForResult(intent, RESULT_VIDEO_CAP);
        mButton.setEnabled(true);
    }

    public void uploadVideo(View view) {
        if (mChosenAccountName == null) {
            return;
        }
        // if an upload video is selected.
        if (mVideoData != null) {
           // mDirectFragment.directLite(mVideoData, mToken);
            mButton.setEnabled(false);
            return;
        }
        // if a video is picked or recorded.
        if (mFileURI != null) {
            Intent uploadIntent = new Intent(this, UploadService.class);
            uploadIntent.setData(mFileURI);
            uploadIntent.putExtra(ACCOUNT_KEY, mChosenAccountName);
            startService(uploadIntent);
            mButton.setEnabled(false);
        }
    }
    
    void showGooglePlayServicesAvailabilityErrorDialog(final int connectionStatusCode) {
        runOnUiThread(new Runnable() {
          public void run() {
            Dialog dialog =
                GooglePlayServicesUtil.getErrorDialog(connectionStatusCode, MainActivity.this,
                    REQUEST_GOOGLE_PLAY_SERVICES);
            dialog.show();
          }
        });
      }
    /** Check that Google Play services APK is installed and up to date. */
    private boolean checkGooglePlayServicesAvailable() {
      final int connectionStatusCode = GooglePlayServicesUtil.isGooglePlayServicesAvailable(this);
      if (GooglePlayServicesUtil.isUserRecoverableError(connectionStatusCode)) {
        showGooglePlayServicesAvailabilityErrorDialog(connectionStatusCode);
        return false;
      }
      return true;
    }

    private void haveGooglePlayServices() {
      // check if there is already an account selected
      if (credential.getSelectedAccountName() == null) {
        // ask user to choose account
        chooseAccount();
      } else {
        // load data
        loadData();
      }
    }

    private void chooseAccount() {
      startActivityForResult(credential.newChooseAccountIntent(), REQUEST_ACCOUNT_PICKER);
    }
    private class InvalidateTokenReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getAction().equals(INVALIDATE_TOKEN_INTENT)) {
                Log.d(InvalidateTokenReceiver.class.getName(), "Invalidating token");
               // GoogleAuthUtil.invalidateToken(MainActivity.this, mToken);
//                mHandler.postDelayed(new Runnable() {
//                    @Override
//                    public void run() {
//                       // tryAuthenticate();
//                    }
//                }, mCurrentBackoff * 1000);
//
//                mCurrentBackoff *= 2;
//                if (mCurrentBackoff == 0) {
//                    mCurrentBackoff = 1;
//                }
            }
        }
    }
    
}
