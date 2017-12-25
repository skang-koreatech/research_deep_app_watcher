package com.example.mobisyslab.eyewatcher;

import android.Manifest;
import android.app.Activity;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Camera;
import android.graphics.ImageFormat;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.hardware.camera2.CameraMetadata;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Environment;
import android.util.Log;
import android.support.annotation.NonNull;
import android.support.v4.app.ActivityCompat;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Timer;
import java.util.TimerTask;
import java.util.UUID;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import android.util.Size;
import android.util.SparseIntArray;
import android.view.Surface;
import android.view.WindowManager;
import android.widget.Toast;

import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.TreeMap;

/**
 * Created by mobisyslab on 2017-12-22.
 */

public class WatcherService extends Service {
    private static final String LOG_TAG = WatcherService.class.getSimpleName();

    private CameraManager mCameraManager;
    private CameraDevice mCameraDevice;
    private SensorManager mSensorManager;
    private Sensor mSensor;
    SensorEventListener mSEListener;
    private float mLight;

    private Timer mTimer;
    private TimerTask mTimerTask;

    private boolean mCameraClosed;
    /**
     * An additional thread for running tasks that shouldn't block the UI.
     */
    private HandlerThread mBackgroundThread;

    /**
     * A {@link Handler} for running tasks in the background.
     */
    private Handler mBackgroundHandler;

    private static final SparseIntArray ORIENTATIONS = new SparseIntArray();

    static {
        ORIENTATIONS.append(Surface.ROTATION_0, 90);
        ORIENTATIONS.append(Surface.ROTATION_90, 0);
        ORIENTATIONS.append(Surface.ROTATION_180, 270);
        ORIENTATIONS.append(Surface.ROTATION_270, 180);
    }

    BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();

            if(action.equals(Intent.ACTION_SCREEN_ON)) {
                Log.d(LOG_TAG, "Screen On");
                startTimerTask();

            } else if(action.equals(Intent.ACTION_SCREEN_OFF)) {
                Log.d(LOG_TAG, "Screen Off");
                stopTimerTask();
            }

        }
    };

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();

        Log.d(LOG_TAG, "Service created");

        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        registerReceiver(receiver, filter);

        mCameraManager = (CameraManager)getSystemService(Context.CAMERA_SERVICE);
        mTimer = new Timer();
        mSensorManager = (SensorManager)getSystemService(Context.SENSOR_SERVICE);
        mSensor = mSensorManager.getDefaultSensor(Sensor.TYPE_LIGHT);
        mSEListener = new SensorEventListener() {
            @Override
            public void onSensorChanged(SensorEvent event) {
                float[] v = event.values;
                switch(event.sensor.getType()) {
                    case Sensor.TYPE_LIGHT:
                        mLight = v[0];
                        Log.d(LOG_TAG, "Light sensor value: " + mLight);
                        Toast.makeText(getApplicationContext(), "Light: " + mLight, Toast.LENGTH_LONG).show();
                        break;
                }
            }

            @Override
            public void onAccuracyChanged(Sensor sensor, int accuracy) {
            }
        };
        startBackgroundThread();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return super.onStartCommand(intent, flags, startId);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(LOG_TAG, "Service destroyed");

        unregisterReceiver(receiver);
        stopTimerTask();
        mTimer.cancel();
        stopBackgroundThread();
    }

    /**
     * Starts a background thread and its {@link Handler}.
     */
    private void startBackgroundThread() {
        mBackgroundThread = new HandlerThread("CameraBackground");
        mBackgroundThread.start();
        mBackgroundHandler = new Handler(mBackgroundThread.getLooper());
    }

    /**
     * Stops the background thread and its {@link Handler}.
     */
    private void stopBackgroundThread() {
        mBackgroundThread.quitSafely();
        try {
            mBackgroundThread.join();
            mBackgroundThread = null;
            mBackgroundHandler = null;
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    private void startTimerTask() {
        stopTimerTask();

        mTimerTask = new TimerTask() {
            int mCount = 0;

            @Override
            public void run() {
                mCount++;
                Log.d(LOG_TAG, "count"+mCount);
                prepareCamera();
            }
        };

        mTimer.schedule(mTimerTask, 0, 10000);
    }

    private void stopTimerTask() {
        if(mTimerTask != null) {
            mTimerTask.cancel();
            mTimerTask = null;
            Log.d(LOG_TAG, "Stop timer");
        }
    }

    private void prepareCamera() {
        try {
            for (String cameraId : mCameraManager.getCameraIdList()) {
                Log.d(LOG_TAG, "Camera Id: " + cameraId);
                CameraCharacteristics characteristics = mCameraManager.getCameraCharacteristics(cameraId);
                if (characteristics.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT) {
                    Log.d(LOG_TAG, "Camera facing front " + cameraId);
                    openCamera(cameraId);
                } else if (characteristics.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK) {
                    Log.d(LOG_TAG, "Camera facing back " + cameraId);
                    //openCamera(cameraId);
                }
            }
        } catch (CameraAccessException e) {
            Log.e(LOG_TAG, "Exception occurred while accessing the camera", e);
        }
    }

    private void openCamera(String cameraId) {
        Log.d(LOG_TAG, "opening camera " + cameraId);
        try {
            if (ActivityCompat.checkSelfPermission(getApplicationContext(), Manifest.permission.CAMERA)
                    == PackageManager.PERMISSION_GRANTED
                    && ActivityCompat.checkSelfPermission(getApplicationContext(),
                    Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    == PackageManager.PERMISSION_GRANTED) {
                mCameraManager.openCamera(cameraId, mCameraDeviceStateCallback, mBackgroundHandler);
            }
        } catch(CameraAccessException e) {
            Log.e(LOG_TAG, "Exception occurred while opeing camera " + cameraId, e);
        }
        mSensorManager.registerListener(mSEListener, mSensor, SensorManager.SENSOR_DELAY_GAME);
    }

    private CameraDevice.StateCallback mCameraDeviceStateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(@NonNull CameraDevice camera) {
            Log.d(LOG_TAG, "Camera " + camera.getId() + " opened");
            mCameraClosed = false;
            mCameraDevice = camera;
            new Handler().postDelayed(new Runnable() {
                @Override
                public void run() {
                    try {
                        takePicture();
                    } catch (CameraAccessException e) {
                        Log.e(LOG_TAG, "Exception occurred while taking a picture");
                    }
                }
            }, 100);
        }

        @Override
        public void onDisconnected(@NonNull CameraDevice camera) {
            camera.close();
        }

        @Override
        public void onError(@NonNull CameraDevice camera, int error) {
            camera.close();
        }
    };

    private void takePicture() throws CameraAccessException {
        if (null == mCameraDevice) {
            Log.e(LOG_TAG, "mCameraDevice is null");
            return;
        }
        final CameraCharacteristics characteristics = mCameraManager.getCameraCharacteristics(mCameraDevice.getId());
        Size[] jpegSizes = null;
        StreamConfigurationMap streamConfigurationMap = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);

        Log.d(LOG_TAG, "Exposure time range: " + characteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE).toString());
        Log.d(LOG_TAG, "Sensitivity range: " + characteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE).toString());
        Log.d(LOG_TAG, "Max frame duration: " + characteristics.get(CameraCharacteristics.SENSOR_INFO_MAX_FRAME_DURATION).toString());

        if (streamConfigurationMap != null) {
            jpegSizes = streamConfigurationMap.getOutputSizes(ImageFormat.JPEG);
        }
        final boolean jpegSizesNotEmpty = jpegSizes != null && 0 < jpegSizes.length;
        int width = jpegSizesNotEmpty ? jpegSizes[0].getWidth() : 640;
        int height = jpegSizesNotEmpty ? jpegSizes[0].getHeight() : 480;
        //int width = 640;
        //int height = 480;

        Log.d(LOG_TAG, "jpegSize width: " + width + " height: " + height);

        final ImageReader reader = ImageReader.newInstance(width, height, ImageFormat.JPEG, 2);
        final List<Surface> outputSurfaces = new ArrayList<>();
        outputSurfaces.add(reader.getSurface());
        reader.setOnImageAvailableListener(onImageAvailableListener, mBackgroundHandler);

        //final CaptureRequest.Builder captureBuilder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE); // completely black images
        //final CaptureRequest.Builder captureBuilder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW); // low brightness
        final CaptureRequest.Builder captureBuilder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_ZERO_SHUTTER_LAG); // low brightness
        captureBuilder.addTarget(reader.getSurface());

        /*
            Need to change exposure and sensitivity setting depending on the light condition
            the current setting below is set based on brief measurement
        * */
        if (mLight > 50.0) {
            captureBuilder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
        } else {
            captureBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF);
            long exposureTime;
            int sensitivity;
            if (mLight <= 50.0 && mLight >= 5.0) {
                exposureTime = Long.valueOf("20000000"); // 1/50 sec
                sensitivity = 800;
            } else if (mLight <= 5.0 && mLight >= 1.0) {
                exposureTime = Long.valueOf("50000000"); // 1/20 sec
                sensitivity = 1000;
            } else {
                exposureTime = Long.valueOf("100000000"); // 1/10 sec
                sensitivity = 1200;
            }
            captureBuilder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposureTime);
            captureBuilder.set(CaptureRequest.SENSOR_SENSITIVITY, sensitivity);
            //captureBuilder.set(CaptureRequest.SENSOR_FRAME_DURATION, Long.valueOf("100000"));
        }

        captureBuilder.set(CaptureRequest.JPEG_ORIENTATION, getOrientation(mCameraDevice.getId())); // if front facing, up side down, if back facing, it's ok

        mCameraDevice.createCaptureSession(outputSurfaces, new CameraCaptureSession.StateCallback() {
                    @Override
                    public void onConfigured(@NonNull CameraCaptureSession session) {
                        try {
                            session.capture(captureBuilder.build(), captureListener, mBackgroundHandler);
                        } catch (final CameraAccessException e) {
                            Log.e(LOG_TAG, " Exception occurred while accessing ", e);
                        }
                    }

                    @Override
                    public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                    }
                }
                , mBackgroundHandler);
    }

    private final CameraCaptureSession.CaptureCallback captureListener = new CameraCaptureSession.CaptureCallback() {
        @Override
        public void onCaptureCompleted(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request,
                                       @NonNull TotalCaptureResult result) {
            super.onCaptureCompleted(session, request, result);
            Log.d(LOG_TAG, "CameraCaptureSession.CaptureCallback: onCaptureCompleted");
            closeCamera();
        }
    };


    private final ImageReader.OnImageAvailableListener onImageAvailableListener =  new ImageReader.OnImageAvailableListener() {
        @Override
        public void onImageAvailable(ImageReader reader) {
            Image image = null;
            try {
                image = reader.acquireLatestImage();
                //image = reader.acquireNextImage();
                //Log.d(LOG_TAG, "onImageAvailable: image format - " + image.getFormat());

                ByteBuffer buffer = image.getPlanes()[0].getBuffer();
                byte[] bytes = new byte[buffer.capacity()];
                //byte[] bytes = new byte[buffer.remaining()];
                buffer.get(bytes);
                saveImageToDisk(bytes);
            } catch (Exception e) {
                Log.e(LOG_TAG, "Exception occurred while reading and saving image", e);
            } finally {
                if (image != null) {
                    image.close();
                    reader.close();
                }
            }
        }
    };

    private void saveImageToDisk(final byte[] bytes) {
        //final String cameraId = this.mCameraDevice == null ? UUID.randomUUID().toString() : this.mCameraDevice.getId();

        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy_MM_dd_HH_mm_ss", Locale.getDefault());
        String filename = "image_"+dateFormat.format(new Date())+".jpg";
        final File path = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES);
        File file = new File(path, filename);
        OutputStream output;
        try {
            output = new FileOutputStream(file);
            output.write(bytes);
            output.close();
            Log.d(LOG_TAG, "Image saved");
            //Toast.makeText(getApplicationContext(), "Capture image saved...", Toast.LENGTH_SHORT).show();
        } catch (final IOException e) {
            Log.e(LOG_TAG, "Exception occurred while saving picture to external storage ", e);
        }
    }

    int getOrientation(String cameraId) {
        final int rotation = ((WindowManager)getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay().getRotation();
        //if(rotation == Surface.ROTATION_0) Log.d(LOG_TAG, "Rotation: 0");
        if(cameraId.equals("0")) // back facing : Google Pixel
            return ORIENTATIONS.get(rotation);
        else if(cameraId.equals("1")) // front facing : Google Pixel
            return ORIENTATIONS.get(rotation+2);
        else
            return ORIENTATIONS.get(rotation);
    }

    private void closeCamera() {
        Log.d(LOG_TAG, "Closing camera " + mCameraDevice.getId());
        if (null != mCameraDevice && !mCameraClosed) {
            mCameraDevice.close();
            mCameraDevice = null;
        }
        mSensorManager.unregisterListener(mSEListener);
    }
}
