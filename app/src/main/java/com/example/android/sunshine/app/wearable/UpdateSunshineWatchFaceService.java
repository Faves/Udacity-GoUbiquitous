package com.example.android.sunshine.app.wearable;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.util.Log;

import com.example.android.sunshine.app.Utility;
import com.example.android.sunshine.app.data.WeatherContract;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.ResultCallback;
import com.google.android.gms.wearable.CapabilityApi;
import com.google.android.gms.wearable.CapabilityInfo;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.MessageApi;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.PutDataMapRequest;
import com.google.android.gms.wearable.Wearable;
import com.google.android.gms.wearable.WearableListenerService;

import java.util.List;
import java.util.Set;

/**
 * Created by Fabien on 14/01/2016.
 */
public class UpdateSunshineWatchFaceService extends WearableListenerService {
    private static final String TAG                     = "UpdSWatchFaceService";

    public static final  String KEY_WEATHER_WEATHER_ID   = "WeatherId";
    public static final  String KEY_WEATHER_CONDITION   = "Condition";
    public static final  String KEY_WEATHER_TEMPERATURE_MIN = "TemperatureMin";
    public static final  String KEY_WEATHER_TEMPERATURE_MAX = "TemperatureMax";
    public static final  String KEY_WEATHER_UPDATE_TIME = "UpdateTime";
    public static final  String PATH_WEATHER_INFO       = "/SunshineWatchFace/WeatherInfo";
    public static final  String PATH_SERVICE_REQUIRE    = "/UpdateSunshineWatchFaceService/Require";

    private GoogleApiClient mGoogleApiClient;

    @Override
    public void onCreate() {
        super.onCreate();
        mGoogleApiClient = new GoogleApiClient.Builder(this)
                .addApi(Wearable.API)
                .build();
    }

    @Override
    public void onDestroy() {
        if (mGoogleApiClient.isConnected() || mGoogleApiClient.isConnecting()) {
            mGoogleApiClient.disconnect();
        }
        super.onDestroy();
    }


    @Override
    public void onDataChanged(DataEventBuffer dataEvents) {
        Log.d(TAG, "onDataChanged: " + dataEvents + " for " + getPackageName());
        for (DataEvent event : dataEvents) {
            Log.d(TAG, "onDataChanged: " + event + " : " + event.getDataItem().getUri().getPath());
            if (PATH_SERVICE_REQUIRE.equals(event.getDataItem().getUri().getPath())) {
                startTask();
            }
        }
    }


    private void startTask() {
        Log.d( TAG, "Start Weather AsyncTask" );
        if ( !mGoogleApiClient.isConnected() ) {
            mGoogleApiClient.connect();
        }

        Task task = new Task(this);
        task.execute();
    }

    private class Task extends AsyncTask<Void, Void, Void> {
        private final String[] FORECAST_COLUMNS = {
                WeatherContract.WeatherEntry.COLUMN_WEATHER_ID,
                WeatherContract.WeatherEntry.COLUMN_SHORT_DESC,
                WeatherContract.WeatherEntry.COLUMN_MAX_TEMP,
                WeatherContract.WeatherEntry.COLUMN_MIN_TEMP
        };
        // these indices must match the projection
        private static final int INDEX_WEATHER_ID = 0;
        private static final int INDEX_SHORT_DESC = 1;
        private static final int INDEX_MAX_TEMP = 2;
        private static final int INDEX_MIN_TEMP = 3;
        private final Context mContext;

        public Task(Context context) {
            super();

            mContext = context;
        }

        @Override
        protected Void doInBackground( Void[] params ) {
            try {
                Log.d( TAG, "Task Running" );

                // Get today's data from the ContentProvider
                String location = Utility.getPreferredLocation(mContext);
                Uri weatherForLocationUri = WeatherContract.WeatherEntry.buildWeatherLocationWithStartDate(
                        location, System.currentTimeMillis());
                Cursor data = getContentResolver().query(weatherForLocationUri, FORECAST_COLUMNS, null,
                        null, WeatherContract.WeatherEntry.COLUMN_DATE + " ASC");
                if (data == null) {
                    return null;
                }
                if (!data.moveToFirst()) {
                    data.close();
                    return null;
                }

                // Extract the weather data from the Cursor
                int weatherId = data.getInt(INDEX_WEATHER_ID);
                String description = data.getString(INDEX_SHORT_DESC);
                double maxTemp = data.getDouble(INDEX_MAX_TEMP);
                double minTemp = data.getDouble(INDEX_MIN_TEMP);
                String formattedMaxTemperature = Utility.formatTemperature(mContext, maxTemp);
                String formattedMinTemperature = Utility.formatTemperature(mContext, minTemp);
                data.close();


                //Send data
                if ( !mGoogleApiClient.isConnected() ) {
                    mGoogleApiClient.connect();
                }
                PutDataMapRequest putDataMapRequest = PutDataMapRequest.create(PATH_WEATHER_INFO);
                DataMap config = putDataMapRequest.getDataMap();

                config.putString(KEY_WEATHER_TEMPERATURE_MIN, formattedMinTemperature);
                config.putString(KEY_WEATHER_TEMPERATURE_MAX, formattedMaxTemperature);
                config.putString(KEY_WEATHER_CONDITION, description);
                config.putInt(KEY_WEATHER_WEATHER_ID, weatherId);
                config.putLong(KEY_WEATHER_UPDATE_TIME, System.currentTimeMillis());

                Wearable.DataApi.putDataItem(mGoogleApiClient, putDataMapRequest.asPutDataRequest())
                        .setResultCallback(new ResultCallback<DataApi.DataItemResult>() {
                            @Override
                            public void onResult(DataApi.DataItemResult dataItemResult) {
                                Log.d(TAG, "SaveData: " + dataItemResult.getStatus() + ", " + dataItemResult.getDataItem().getUri());

                                mGoogleApiClient.disconnect();
                            }
                        });
            }
            catch ( Exception e ) {
                Log.d( TAG, "Task Fail: " + e );
            }
            return null;
        }
    }


}
