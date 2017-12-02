/*
 * Copyright (C) The Android Open Source Project
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
package com.google.android.gms.samples.vision.face.googlyeyes;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.PointF;
import android.os.Bundle;
import android.os.Environment;
import android.os.Message;
import android.os.SystemClock;
import android.support.annotation.NonNull;
import android.support.design.widget.Snackbar;
import android.support.v4.app.ActivityCompat;
import android.support.v4.app.NotificationCompat;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.util.SparseArray;
import android.view.View;
import android.widget.Button;
import android.widget.Toast;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GoogleApiAvailability;
import com.google.android.gms.vision.CameraSource;
import com.google.android.gms.vision.Detector;
import com.google.android.gms.vision.Frame;
import com.google.android.gms.vision.MultiProcessor;
import com.google.android.gms.vision.Tracker;
import com.google.android.gms.vision.face.Face;
import com.google.android.gms.vision.face.FaceDetector;
import com.google.android.gms.samples.vision.face.googlyeyes.ui.camera.CameraSourcePreview;
import com.google.android.gms.samples.vision.face.googlyeyes.ui.camera.GraphicOverlay;
import com.google.android.gms.vision.face.Landmark;
import com.google.android.gms.vision.face.LargestFaceFocusingProcessor;

import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;


/**
 * Activity for Googly Eyes, an app that uses the camera to track faces and superimpose Googly Eyes
 * animated graphics over the eyes.  The app also detects whether the eyes are open or closed,
 * drawing the eyes in the correct state.<p>
 *
 * This app supports both a front facing mode and a rear facing mode, which demonstrate different
 * API functionality trade-offs:<p>
 *
 * Front facing mode uses the device's front facing camera to track one user, in a "selfie" fashion.
 * The settings for the face detector and its associated processing pipeline are set to optimize for
 * the single face case, where the face is relatively large.  These factors allow the face detector
 * to be faster and more responsive to quick motion.<p>
 *
 * Rear facing mode uses the device's rear facing camera to track any number of faces.  The settings
 * for the face detector and its associated processing pipeline support finding multiple faces, and
 * attempt to find smaller faces in comparison to the front facing mode.  But since this requires
 * more scanning at finer levels of detail, rear facing mode may not be as responsive as front
 * facing mode.<p>
 */
public final class GooglyEyesActivity extends AppCompatActivity {
    private static final String TAG = "GooglyEyes";
    private static final int RC_HANDLE_GMS = 9001;
    // permission request codes need to be < 256
    private static final int RC_HANDLE_CAMERA_PERM = 2;
    private CameraSource mCameraSource = null;
    private CameraSourcePreview mPreview;
    private GraphicOverlay mGraphicOverlay;
    private boolean mIsFrontFacing = true;

    static {
        System.loadLibrary("native-lib");
    }

    //==============================================================================================
    // Activity Methods
    //==============================================================================================

    /**
     * Initializes the UI and initiates the creation of a face detector.
     */
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);

        mPreview = (CameraSourcePreview) findViewById(R.id.preview);
        mGraphicOverlay = (GraphicOverlay) findViewById(R.id.faceOverlay);


        final Button button = (Button) findViewById(R.id.flipButton);
        button.setOnClickListener(mFlipButtonListener);

        if (savedInstanceState != null) {
            mIsFrontFacing = savedInstanceState.getBoolean("IsFrontFacing");
        }

        // Check for the camera permission before accessing the camera.  If the
        // permission is not granted yet, request permission.
        int rc = ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA);
        if (rc == PackageManager.PERMISSION_GRANTED) {
            createCameraSource();
        } else {
            requestCameraPermission();
        }
    }

    /**
     * Handles the requesting of the camera permission.  This includes showing a "Snackbar" message
     * of why the permission is needed then sending the request.
     */
    private void requestCameraPermission() {
        Log.w(TAG, "Camera permission is not granted. Requesting permission");

        final String[] permissions = new String[]{Manifest.permission.CAMERA};

        if (!ActivityCompat.shouldShowRequestPermissionRationale(this,
                Manifest.permission.CAMERA)) {
            ActivityCompat.requestPermissions(this, permissions, RC_HANDLE_CAMERA_PERM);
            return;
        }

        final Activity thisActivity = this;

        View.OnClickListener listener = new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                ActivityCompat.requestPermissions(thisActivity, permissions,
                        RC_HANDLE_CAMERA_PERM);
            }
        };

        Snackbar.make(mGraphicOverlay, R.string.permission_camera_rationale,
                Snackbar.LENGTH_INDEFINITE)
                .setAction(R.string.ok, listener)
                .show();
    }

    /**
     * Restarts the camera.
     */
    @Override
    protected void onResume() {
        super.onResume();

        startCameraSource();
    }

    /**
     * Stops the camera.
     */
    @Override
    protected void onPause() {
        super.onPause();
        mPreview.stop();
    }

    /**
     * Releases the resources associated with the camera source, the associated detector, and the
     * rest of the processing pipeline.
     */
    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mCameraSource != null) {
            mCameraSource.release();
            theThreadLeft.kill();
            theThreadRight.kill();
        }
    }

    /**
     * Callback for the result from requesting permissions. This method is invoked for every call on
     * {@link #requestPermissions(String[], int)}.<p>
     *
     * <strong>Note:</strong> It is possible that the permissions request interaction with the user
     * is interrupted. In this case you will receive empty permissions and results arrays which
     * should be treated as a cancellation.<p>
     *
     * @param requestCode  The request code passed in {@link #requestPermissions(String[], int)}.
     * @param permissions  The requested permissions. Never null.
     * @param grantResults The grant results for the corresponding permissions
     *                     which is either {@link PackageManager#PERMISSION_GRANTED}
     *                     or {@link PackageManager#PERMISSION_DENIED}. Never null.
     * @see #requestPermissions(String[], int)
     */
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        if (requestCode != RC_HANDLE_CAMERA_PERM) {
            Log.d(TAG, "Got unexpected permission result: " + requestCode);
            super.onRequestPermissionsResult(requestCode, permissions, grantResults);
            return;
        }

        if (grantResults.length != 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            Log.d(TAG, "Camera permission granted - initialize the camera source");
            // we have permission, so create the camerasource
            createCameraSource();
            return;
        }

        Log.e(TAG, "Permission not granted: results len = " + grantResults.length +
                " Result code = " + (grantResults.length > 0 ? grantResults[0] : "(empty)"));

        DialogInterface.OnClickListener listener = new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int id) {
                finish();
            }
        };

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Face Tracker sample")
                .setMessage(R.string.no_camera_permission)
                .setPositiveButton(R.string.ok, listener)
                .show();
    }

    //==============================================================================================
    // UI
    //==============================================================================================

    /**
     * Saves the camera facing mode, so that it can be restored after the device is rotated.
     */
    @Override
    public void onSaveInstanceState(Bundle savedInstanceState) {
        super.onSaveInstanceState(savedInstanceState);
        savedInstanceState.putBoolean("IsFrontFacing", mIsFrontFacing);
    }

    /**
     * Toggles between front-facing and rear-facing modes.
     */
    private View.OnClickListener mFlipButtonListener = new View.OnClickListener() {
        public void onClick(View v) {
            mIsFrontFacing = !mIsFrontFacing;
            if (mCameraSource != null) {
                mCameraSource.release();
                mCameraSource = null;
                theThreadLeft.kill();
                theThreadRight.kill();
            }
            createCameraSource();
            startCameraSource();
        }
    };

    //==============================================================================================
    // Detector
    //==============================================================================================

    /**
     * Creates the face detector and associated processing pipeline to support either front facing
     * mode or rear facing mode.  Checks if the detector is ready to use, and displays a low storage
     * warning if it was not possible to download the face library.
     */
    @NonNull
    private MyFaceDetector createFaceDetector(Context context) {
        // For both front facing and rear facing modes, the detector is initialized to do landmark
        // detection (to find the eyes), classification (to determine if the eyes are open), and
        // tracking.
        //
        // Use of "fast mode" enables faster detection for frontward faces, at the expense of not
        // attempting to detect faces at more varied angles (e.g., faces in profile).  Therefore,
        // faces that are turned too far won't be detected under fast mode.
        //
        // For front facing mode only, the detector will use the "prominent face only" setting,
        // which is optimized for tracking a single relatively large face.  This setting allows the
        // detector to take some shortcuts to make tracking faster, at the expense of not being able
        // to track multiple faces.
        //
        // Setting the minimum face size not only controls how large faces must be in order to be
        // detected, it also affects performance.  Since it takes longer to scan for smaller faces,
        // we increase the minimum face size for the rear facing mode a little bit in order to make
        // tracking faster (at the expense of missing smaller faces).  But this optimization is less
        // important for the front facing case, because when "prominent face only" is enabled, the
        // detector stops scanning for faces after it has found the first (large) face.
        FaceDetector detector = new FaceDetector.Builder(context)
                .setLandmarkType(FaceDetector.ALL_LANDMARKS)
                .setClassificationType(FaceDetector.ALL_CLASSIFICATIONS)
                .setTrackingEnabled(true)
                .setMode(FaceDetector.FAST_MODE)
                .setProminentFaceOnly(mIsFrontFacing)
                .setMinFaceSize(mIsFrontFacing ? 0.55f : 0.15f)
                .build();
        MyFaceDetector myFaceDetector =  new MyFaceDetector(detector);


        Detector.Processor<Face> processor;
        if (mIsFrontFacing) {
            // For front facing mode, a single tracker instance is used with an associated focusing
            // processor.  This configuration allows the face detector to take some shortcuts to
            // speed up detection, in that it can quit after finding a single face and can assume
            // that the nextIrisPosition face position is usually relatively close to the last seen
            // face position.
            Tracker<Face> tracker = new GooglyFaceTracker(mGraphicOverlay);
            processor = new LargestFaceFocusingProcessor.Builder(myFaceDetector, tracker).build();
        } else {
            // For rear facing mode, a factory is used to create per-face tracker instances.  A
            // tracker is created for each face and is maintained as long as the same face is
            // visible, enabling per-face state to be maintained over time.  This is used to store
            // the iris position and velocity for each face independently, simulating the motion of
            // the eyes of any number of faces over time.
            //
            // Both the front facing mode and the rear facing mode use the same tracker
            // implementation, avoiding the need for any additional code.  The only difference
            // between these cases is the choice of Processor: one that is specialized for tracking
            // a single face or one that can handle multiple faces.  Here, we use MultiProcessor,
            // which is a standard component of the mobile vision API for managing multiple items.
            MultiProcessor.Factory<Face> factory = new MultiProcessor.Factory<Face>() {
                @Override
                public Tracker<Face> create(Face face) {
                    return new GooglyFaceTracker(mGraphicOverlay);
                }
            };
            processor = new MultiProcessor.Builder<>(factory).build();
        }

        myFaceDetector.setProcessor(processor);

        if (!myFaceDetector.isOperational()) {
            // Note: The first time that an app using face API is installed on a device, GMS will
            // download a native library to the device in order to do detection.  Usually this
            // completes before the app is run for the first time.  But if that download has not yet
            // completed, then the above call will not detect any faces.
            //
            // isOperational() can be used to check if the required native library is currently
            // available.  The detector will automatically become operational once the library
            // download completes on device.
            Log.w(TAG, "Face detector dependencies are not yet available.");

            // Check for low storage.  If there is low storage, the native library will not be
            // downloaded, so detection will not become operational.
            IntentFilter lowStorageFilter = new IntentFilter(Intent.ACTION_DEVICE_STORAGE_LOW);
            boolean hasLowStorage = registerReceiver(null, lowStorageFilter) != null;

            if (hasLowStorage) {
                Toast.makeText(this, R.string.low_storage_error, Toast.LENGTH_LONG).show();
                Log.w(TAG, getString(R.string.low_storage_error));
            }
        }
        return myFaceDetector;
    }

    //==============================================================================================
    // Camera Source
    //==============================================================================================

    /**
     * Creates the face detector and the camera.
     */
    private void createCameraSource() {
        Context context = getApplicationContext();
        MyFaceDetector detector1 = createFaceDetector(context);

        int facing = CameraSource.CAMERA_FACING_FRONT;
        if (!mIsFrontFacing) {
            facing = CameraSource.CAMERA_FACING_BACK;
        }

        // The camera source is initialized to use either the front or rear facing camera.  We use a
        // relatively low resolution for the camera preview, since this is sufficient for this app
        // and the face detector will run faster at lower camera resolutions.
        //
        // However, note that there is a speed/accuracy trade-off with respect to choosing the
        // camera resolution.  The face detector will run faster with lower camera resolutions,
        // but may miss smaller faces, landmarks, or may not correctly detect eyes open/closed in
        // comparison to using higher camera resolutions.  If you have any of these issues, you may
        // want to increase the resolution.
        mCameraSource = new CameraSource.Builder(context, detector1)
                .setFacing(facing)
/*----------------------------------------------------------------------------------------------*/
                .setRequestedPreviewSize(320, 240) /* width, height */
                .setRequestedFps(30.0f) //FPS number
/*----------------------------------------------------------------------------------------------*/
                .setAutoFocusEnabled(true)
                .build();

    }

    /**
     * Starts or restarts the camera source, if it exists.  If the camera source doesn't exist yet
     * (e.g., because onResume was called before the camera source was created), this will be called
     * again when the camera source is created.
     */
    private void startCameraSource() {
        // check that the device has play services available.
        int code = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(
                getApplicationContext());
        if (code != ConnectionResult.SUCCESS) {
            Dialog dlg =
                    GoogleApiAvailability.getInstance().getErrorDialog(this, code, RC_HANDLE_GMS);
            dlg.show();
        }

        if (mCameraSource != null) {
            try {
                mPreview.start(mCameraSource, mGraphicOverlay);


            } catch (IOException e) {
                Log.e(TAG, "Unable to start camera source.", e);
                mCameraSource.release();
                mCameraSource = null;
                theThreadLeft.kill();
                theThreadRight.kill();
            }
        }
    }

    /*-------------------------------------------------------------------------------------------
     This where declare our frame and coordinate variables to be sent to and received from the native side.
     --------------------------------------------------------------------------------------------*/
    public volatile PointF leftEyeJNI, rightEyeJNI;
    public volatile byte[] frameLeftJNI, frameRightJNI;
    public volatile int frameWidth, frameHeight;
    MyLeftWorkerThread theThreadLeft;
    MyRightWorkerThread theThreadRight;
    public volatile float[] leftEyePupil = {0.0f,0.0f};
    public volatile float[] rightEyePupil = {0.0f,0.0f};

    volatile float distanceBetweenEyes = 0.0f;

    /**
     * Updates the distance between two eyes. Necessary for the ROI calculations on the native side.
     *
      */
    public void distanceUpdate(){
        if(rightEyeJNI != null && leftEyeJNI != null) {
            distanceBetweenEyes = (float) Math.sqrt(
                    Math.pow(rightEyeJNI.x - leftEyeJNI.x, 2) +
                            Math.pow(rightEyeJNI.y - leftEyeJNI.y, 2));
        }
    }


    /*-------------------------------------------------------------------------------------------
     --------------------------------------------------------------------------------------------*/

    class MyFaceDetector extends Detector<Face>{
        private Detector<Face> mDelegate;

        MyFaceDetector(Detector<Face> delegate) {
            mDelegate = delegate;
            theThreadLeft = new MyLeftWorkerThread();
            theThreadLeft.start();
            theThreadRight = new MyRightWorkerThread();
            theThreadRight.start();
        }
        public SparseArray<Face> detect(Frame frame) {
            //this is where we get the Frame.
            frameHeight = frame.getMetadata().getHeight();
            frameWidth = frame.getMetadata().getWidth();

            if(frame.getGrayscaleImageData().hasArray()) {

                // Note that the array size is width * height * 1.5 for raw YUV images. We only take
                // the first 2/3 of the array, thus getting the gray scale (Y part) image without making any
                // conversion.
                // YUV 1 pixel layout = YYYY YYYY UVUV (12bit - 1.5 byte)
                frameLeftJNI = Arrays.copyOf(frame.getGrayscaleImageData().array(),  frameHeight * frameWidth );
                frameRightJNI = Arrays.copyOf(frame.getGrayscaleImageData().array(),  frameHeight * frameWidth );
            }

            // activates the threads
                theThreadLeft.AddFrameEvent();
                theThreadRight.AddFrameEvent();

            return mDelegate.detect(frame);
        }

        public boolean isOperational() {
            return mDelegate.isOperational();
        }

        public boolean setFocus(int id) {
            return mDelegate.setFocus(id);
        }

    }

    public volatile GraphicOverlay mOverlay;
    public volatile GooglyEyesGraphic mEyesGraphic;

    class GooglyFaceTracker extends Tracker<Face> {
        private static final float EYE_CLOSED_THRESHOLD = 0.0f;


        // Record the previously seen proportions of the landmark locations relative to the bounding box
        // of the face.  These proportions can be used to approximate where the landmarks are within the
        // face bounding box if the eye landmark is missing in a future update.
        private Map<Integer, PointF> mPreviousProportions = new HashMap<>();

        // Similarly, keep track of the previous eye open state so that it can be reused for
        // intermediate frames which lack eye landmarks and corresponding eye state.
        private boolean mPreviousIsLeftOpen = true;
        private boolean mPreviousIsRightOpen = true;


        //==============================================================================================
        // Methods
        //==============================================================================================

        GooglyFaceTracker(GraphicOverlay overlay) {
            mOverlay = overlay;
        }

        /**
         * Resets the underlying googly eyes graphic and associated physics state.
         */
        @Override
        public void onNewItem(int id, Face face) {
            mEyesGraphic = new GooglyEyesGraphic(mOverlay);
        }

        /**
         * Updates the positions and state of eyes to the underlying graphic, according to the most
         * recent face detection results.  The graphic will render the eyes and simulate the motion of
         * the iris based upon these changes over time.
         */
        @Override
        public void onUpdate(FaceDetector.Detections<Face> detectionResults, Face face) {
            mOverlay.add(mEyesGraphic);

            updatePreviousProportions(face);

            PointF leftPosition = getLandmarkPosition(face, Landmark.LEFT_EYE);
            PointF rightPosition = getLandmarkPosition(face, Landmark.RIGHT_EYE);

            /* -----------------------------------------------------------------------------*
            /   We get the positions updated with each frame that tracker detects
            */
            leftEyeJNI = leftPosition;
            rightEyeJNI = rightPosition;
            distanceUpdate();

            float leftOpenScore = face.getIsLeftEyeOpenProbability();
            boolean isLeftOpen;
                isLeftOpen = (leftOpenScore > EYE_CLOSED_THRESHOLD);
                mPreviousIsLeftOpen = isLeftOpen;

            float rightOpenScore = face.getIsRightEyeOpenProbability();
            boolean isRightOpen;
                isRightOpen = (rightOpenScore > EYE_CLOSED_THRESHOLD);
                mPreviousIsRightOpen = isRightOpen;

            mEyesGraphic.updateEyes(leftPosition, isLeftOpen, rightPosition, isRightOpen);


        }

        /**
         * Hide the graphic when the corresponding face was not detected.  This can happen for
         * intermediate frames temporarily (e.g., if the face was momentarily blocked from
         * view).
         */
        @Override
        public void onMissing(FaceDetector.Detections<Face> detectionResults) {
            mOverlay.remove(mEyesGraphic);
        }

        /**
         * Called when the face is assumed to be gone for good. Remove the googly eyes graphic from
         * the overlay.
         */
        @Override
        public void onDone() {
            mOverlay.remove(mEyesGraphic);
        }

        //==============================================================================================
        // Private
        //==============================================================================================

        private void updatePreviousProportions(Face face) {
            for (Landmark landmark : face.getLandmarks()) {
                PointF position = landmark.getPosition();
                float xProp = (position.x - face.getPosition().x) / face.getWidth();
                float yProp = (position.y - face.getPosition().y) / face.getHeight();
                mPreviousProportions.put(landmark.getType(), new PointF(xProp, yProp));
            }
        }

        /**
         * Finds a specific landmark position, or approximates the position based on past observations
         * if it is not present.
         */
        private PointF getLandmarkPosition(Face face, int landmarkId) {
            for (Landmark landmark : face.getLandmarks()) {
                if (landmark.getType() == landmarkId) {
                    return landmark.getPosition();
                }
            }

            PointF prop = mPreviousProportions.get(landmarkId);
            if (prop == null) {
                return null;
            }

            float x = face.getPosition().x + (prop.x * face.getWidth());
            float y = face.getPosition().y + (prop.y * face.getHeight());
            return new PointF(x, y);
        }
    }

    public class MyLeftWorkerThread extends Thread{
        private boolean runWorkerThread;
        private boolean isFrameWaiting;
        private float[] tempFloatArray;
        public MyLeftWorkerThread(){
            super();
            runWorkerThread = true;
        }
        public void AddFrameEvent(){

            synchronized (this) {
                isFrameWaiting = true;
            }
        }
        public boolean TakeFrameEvent(){
            synchronized (this){
                boolean temp = isFrameWaiting;
                isFrameWaiting= false;
                return temp;
            }
        }
        public  void kill (){
            runWorkerThread = false;
        }

        @Override
        public void run() {
            while (runWorkerThread){
                    if(TakeFrameEvent()){
                            if(leftEyeJNI != null) {
                                tempFloatArray =leftEyeFromJNI(frameLeftJNI, leftEyeJNI.x, leftEyeJNI.y, frameHeight, frameWidth, distanceBetweenEyes);
                                //if detections does not fail, update
                                if(tempFloatArray[0] != 0.0f && tempFloatArray[1] != 0.0f){
                                    leftEyePupil = tempFloatArray;
                                } else if (Math.abs(leftEyePupil[0] - leftEyeJNI.x) > 30  ){
                                    leftEyePupil[0] = 0.0f;
                                }
                                mEyesGraphic.updateLeftPupil(new PointF(leftEyePupil[0], leftEyePupil[1]));
                                // triggers a redraw to update the overlay to show the latest detection results on the screen (dots)
                                mEyesGraphic.postInvalidate();
                            } else{
                                try {
                                    Thread.sleep(5);
                                } catch (Exception e){
                                    e.printStackTrace();
                                }
                            }
                    }

            }
        }
    }

    public class MyRightWorkerThread extends Thread {
        private boolean runWorkerThread;
        private int counter; // this is for debugging only.
        private long starttime, endtime;
        private boolean isFrameWaiting;
        private float[]  tempFloatArray;

        public MyRightWorkerThread() {
            super();
            runWorkerThread = true;
            counter = 0;
            starttime = 0;
        }

        public void AddFrameEvent() {
                synchronized (this){
                    isFrameWaiting = true;
                }
        }

        public boolean TakeFrameEvent(){
            synchronized (this) {
                boolean temp = isFrameWaiting;
                isFrameWaiting = false;
                return temp;
            }
        }
        public  void kill (){
            runWorkerThread = false;
        }
        @Override
        public void run(){
            while (runWorkerThread){
                if(TakeFrameEvent()) {
                    if (rightEyeJNI != null){
                        if(starttime == 0){
                            starttime = SystemClock.currentThreadTimeMillis();
                        }

                        counter++;
                        tempFloatArray = rightEyeFromJNI(frameRightJNI, rightEyeJNI.x, rightEyeJNI.y, frameHeight, frameWidth, distanceBetweenEyes);
                        //if detection does not fail, update the position
                        if(tempFloatArray[0] != 0.0f && tempFloatArray[1] != 0.0f){
                            rightEyePupil = tempFloatArray;

                        } else if(Math.abs(rightEyePupil[0] - rightEyeJNI.x) > 30){
                            rightEyePupil[0] = 0.0f;
                        }
                        mEyesGraphic.updateRightPupil(new PointF(rightEyePupil[0], rightEyePupil[1]));
                        mEyesGraphic.postInvalidate();
                        if (counter == 1) {
                            endtime = SystemClock.currentThreadTimeMillis() - starttime;
                            counter = 0;
                            endtime = 0;
                            starttime = 0;
                        }
                    }

                }
                else{
                    try {
                        Thread.sleep(5);
                    } catch (Exception e){
                        e.printStackTrace();
                    }
                }
            }
        }
    }


    public native float[] leftEyeFromJNI(byte[] frame, float leftX, float leftY, int height, int width, float distance);
    public native float[] rightEyeFromJNI(byte[] frame, float rightX, float rightY, int height, int width, float distance);
}

