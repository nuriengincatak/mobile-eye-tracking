# Mobile Eye Tracking

The aim of this project is  to implement a mobile version of eye tracking algorithm taking into account the limitations and advantages of smartphones.

## Getting Started


### For running the .apk file

* OpenCV libraries are included in the apk file. 

* For the first time of using the app, you need to have an internet connection to download 
Google Mobile Vision library (around 25mb). If you do not see the rectangles, Google Play Services has probably not finished loading the
required files. Usually, the download is taken care of at application install time, but this is not guaranteed. This library is shared between apps
and not needed to be downloaded again.

### For building

For building the project on Android Studio, you need to change the path to where your OpenCV files are in CMakeLists.txt file.

```
include_directories(C:/[YOUR_PATH]/OpenCV-android-sdk/sdk/native/jni/include)
set(OpenCV_STATIC on)
set(OpenCV_DIR C:/[YOUR_PATH]/OpenCV-android-sdk/sdk/native/jni)
```

Please remove these lines from your gradle file to have a universal APK file:
```
splits {
        abi {
            enable true
            reset()
            include 'x86', 'armeabi', 'armeabi-v7a', 'mips'
            universalApk false
        }
    }
```
Note that this will increase the size of your .apk file, substantially.
## How this app works

* There are 3 different threads running in this app excluding the Main UI thread. 
First thread runs the face detection and it detects landmark points on the face. This thread updates
leftEyeJNI and rightEyeJNI PointF variables with the rough eye locations. 

* When these two variables become non-null, MyFaceDetector class in the GooglyEyesAcitivity.java sets "isFrameWaiting" flag
to true in the mThreadLeft and mThreadRight worker threads, starting eye-pupil detection in each. This flag is set to true every time a frame is 
received, giving each thread at least (1000/Fps) milisecond to process the frame and update the accurate pupil locations. The frame is also copied 
every time a new thread is received. 

* However, notice that this copy is a shallow copy and will be rewritten with the next frame. This is by design
because getting a deep copy consumes a certain amount of time as well as memory and volatile keyword in Java guarantees that reading thread
will see the latest completely writen version, but not the file while it is being written on.

* Another time saving trick used is getting gray scale image directly from the image ByteBuffer. Android camera (used in this project) 
and camera2 (can be used in the future) APIs both output in YUV format. What is different in this format from BGR is that grayscale image
bytes can be directly extracted without any calculations by taking the first 2/3 of the bytes (Y part in YUV) from the image buffer. 
In the old model, on the other hand, YUV -> BGR -> GRAY conversions were used to on each frame. Bear in mind that we only need grayscale 
image for pupil detection.
```
YUV 1 pixel layout = YYYY YYYY UVUV (12bit - 1.5 byte)
```

* The biggest time saving comes from parallelizing each eye detection. The fact that eye detection is the most time consuming part of the app, 
in addition to the fact that 98% of mobile devices have at least 2, 77% of them have at least 4 threads on their CPUs, and maintaining a responsive UI 
being a priority on any mobile device have made the case for multithreading. 

* As a result, this app runs approximately 20 FPS at 55% CPU usage (with a frame WxH of 320x240) on the test device Moto G4 Plus. Eye tracking 
threads run 27-28 times every second but they sometimes cannot detect. 
Rate of success depends on the existence of eyeglasses and lighting conditions. If you want higher speed decrease 'fastSize_width' variable in native-lib.cpp.


###Extra

* Two blue circles is shown on the face when blink is detected on both eyes. 
But this is based on landmark points only. EYE_CLOSED_THRESHOLD in  GooglyEyesAcitivity.java is currently 
set to 0.0f and up to 0.8f, it gives low number of false positives.

* Although, the app itself supports flipping the camera and rotating the phone, accurate eye pupil detection does not. This part can be added in the future.

## Layout of the source files

* CameraSourcePreview.java and GraphicOverlay.java files are taken from the sample app and used for drawing the locations on the screen 
and showing the camera frames. Minimal changes made on them.

* CameraSource.java open source version can be found online, however, here we opt to use the one included in the Vision library for the sake of conciseness.
The code in this file handles the part related to camera frames.

* GooglyEyesGraphic extends a part of GraphicOverlay and used for drawing the ROI and pupils.

* GooglyEyesActivity is the main activity file. First 400 or so lines mostly consists boilerplate code for setting up the detector, asking for camera permissions, 
loading the native library, controlling each thread's lifecycle and triggering the download for Vision Library. After this, MyFaceDetector class extends the 
face detector for getting the latest frames and triggering pupil threads. This is followed by face tracker implementation. This is where we get the latest 
rough eye positions from the landmark detector. At the end, we have the implementation of 2 threads.

* On the native side, there is native-lib.cpp file in which eye-pupil locations are calculated. To change the ROI size, 
EYE_ROI_PROPORTION_WIDTH and EYE_ROI_PROPORTION_HEIGHT can be changed but the numbers they are set gave the best result in our test. 
For people with eyeglasses, roughly halving those values should give the most clean results. It goes without saying that this app works better on people
without eyeglasses.

## Built With

* [Google Mobile Vision API](https://developers.google.com/vision/) - For face and landmark detection
* [OpenCV](https://opencv.org/) - For libraries necessary for pupil tracking
* [EyeTab](https://github.com/errollw/EyeTab/tree/master/EyeTab) - Used for pupil tracking on the native side.


## Authors

* **Nuri Engin Catak** - (naatak@mail.uni-mannheim.de)

## Licenses

Sample Googly app is under Apache 2.0 License. Google Mobile Vision API is under Android SDK License. This means the Mobile Vision Library 
is free to use, even for commercial applications.

## Acknowledgments

* [Googly Eyes](https://github.com/googlesamples/android-vision/tree/master/visionSamples/googly-eyes) - Served as a starting point for this app
* [Fabian Timm and Erhardt Barth](http://www.inb.uni-luebeck.de/fileadmin/files/PUBPDFS/TiBa11b.pdf) - The creators of this eye pupil localization algorithm

