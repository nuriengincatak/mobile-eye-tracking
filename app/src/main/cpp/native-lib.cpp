//
// Created by Engin on 13.11.2017.
//
#include <jni.h>
#include <stdlib.h>
#include <iostream>
#include <string>
#include <unistd.h>
#include <opencv2/highgui/highgui.hpp>
#include <opencv2/imgproc/imgproc.hpp>
#include <opencv2/photo/photo.hpp>z

using namespace std;
using namespace cv;

// GLOBAL VARIABLES
    const int fastSize_width = 30;
    const int DARKNESS_WEIGHT_SCALE = 100;
    const float EYE_ROI_PROPORTION_WIDTH = 0.40f;
    const float EYE_ROI_PROPORTION_HEIGHT = 0.30f;
// 80 and 45

void erase_specular(Mat eye_grey) {

    // Rather arbitrary decision on how large a specularity may be
    int max_spec_contour_area = (eye_grey.size().width + eye_grey.size().height)/2;

    GaussianBlur(eye_grey, eye_grey, Size(5,5), 0);
    const Mat ERASE_SPEC_KERNEL = getStructuringElement(MORPH_ELLIPSE, Size(5, 5));

    // Close to suppress eyelashes
    morphologyEx(eye_grey, eye_grey, MORPH_CLOSE, ERASE_SPEC_KERNEL);

    // Compute thresh value (using of highest and lowest pixel values)
    double m, M; // m(in) and (M)ax values in image
    minMaxLoc(eye_grey, &m, &M, NULL, NULL);
    double thresh = (m + M) * 3/4;

    // Threshold the image
    Mat eye_thresh;
    threshold(eye_grey, eye_thresh, thresh, 255, THRESH_BINARY);

    // Find all contours in threshed image (possible specularities)
    vector< vector<Point> > all_contours, contours;
    findContours(eye_thresh, all_contours, CV_RETR_LIST, CV_CHAIN_APPROX_SIMPLE);

    // Only save small ones (assumed to be spec.s)
    for (int i=0; i<all_contours.size(); i++){
        if( contourArea(all_contours[i]) < max_spec_contour_area )
            contours.push_back(all_contours[i]);
    }

    // Draw the contours into an inpaint mask
    Mat small_contours_mask = Mat::zeros(eye_grey.size(), eye_grey.type());
    drawContours(small_contours_mask, contours, -1, 255, -1);
    dilate(small_contours_mask, small_contours_mask, ERASE_SPEC_KERNEL);

    // Inpaint within contour bounds
    inpaint(eye_grey, small_contours_mask, eye_grey, 2, INPAINT_TELEA);
}

Mat get_centermap(Mat& eye_grey) {

    // Calculate image gradients
    Mat grad_x, grad_y;
    Sobel(eye_grey, grad_x, CV_32F, 1, 0, 5);
    Sobel(eye_grey, grad_y, CV_32F, 0, 1, 5);

    // Get magnitudes of gradients, and calculate thresh
    Mat mags;
    Scalar mean, stddev;
    magnitude(grad_x, grad_y, mags);
    meanStdDev(mags, mean, stddev);
    int mag_thresh = stddev.val[0] / 2 + mean.val[0];

    // Threshold out gradients with mags which are too low
    grad_x.setTo(0, mags < mag_thresh);
    grad_y.setTo(0, mags < mag_thresh);

    // Normalize gradients
    grad_x = grad_x / (mags+1); // (+1 is hack to guard against div by 0)
    grad_y = grad_y / (mags+1);

    // Initialize 1d vectors of x and y indicies of Mat
    vector<int> x_inds_vec, y_inds_vec;
    for(int i = 0; i < eye_grey.size().width; i++)
        x_inds_vec.push_back(i);
    for(int i = 0; i < eye_grey.size().height; i++)
        y_inds_vec.push_back(i);

    // Repeat vectors to form indices Mats
    Mat x_inds(x_inds_vec), y_inds(y_inds_vec);
    x_inds = repeat(x_inds.t(), eye_grey.size().height, 1);
    y_inds = repeat(y_inds, 1, eye_grey.size().width);
    x_inds.convertTo(x_inds, CV_32F);	// Has to be float for arith. with dx, dy
    y_inds.convertTo(y_inds, CV_32F);

    // Set-up Mats for main loop
    Mat ones = Mat::ones(x_inds.rows, x_inds.cols, CV_32F);	// for re-use with creating normalized disp. vecs
    Mat darkness_weights = (255 - eye_grey) / DARKNESS_WEIGHT_SCALE;
    Mat accumulator = Mat::zeros(eye_grey.size(), CV_32F);
    Mat diffs, dx, dy;

    // Loop over all pixels, testing each as a possible center
    for(int y = 0; y < eye_grey.rows; ++y) {

        // Get pointers for each row
        float* grd_x_p = grad_x.ptr<float>(y);
        float* grd_y_p = grad_y.ptr<float>(y);
        uchar* d_w_p = darkness_weights.ptr<uchar>(y);

        for(int x = 0; x < eye_grey.cols; ++x) {

            // Deref and increment pointers
            float grad_x_val = *grd_x_p++;
            float grad_y_val = *grd_y_p++;

            // Skip if no gradient
            if(grad_x_val == 0 && grad_y_val == 0)
                continue;

            dx = ones * x - x_inds;
            dy = ones * y - y_inds;

            magnitude(dx, dy, mags);
            dx = dx / mags;
            dy = dy / mags;

            diffs = (dx * grad_x_val + dy * grad_y_val) * *d_w_p++;
            diffs.setTo(0, diffs < 0);

            accumulator = accumulator + diffs;
        }
    }

    // Normalize and convert accumulator
    accumulator = accumulator / eye_grey.total();
    normalize(accumulator, accumulator, 0, 255, NORM_MINMAX);
    accumulator.convertTo(accumulator, CV_8U);

    return accumulator;
}

Point find_eye_center(Mat eye_grey){

    Mat eye_grey_small;

   // Resize the image to a constant fast size, only downscales --Nuri
    float scale = 1.0f;
    if(eye_grey.size().width > fastSize_width) {
        scale = fastSize_width / (float) eye_grey.size().width; // fastSize == 40
        resize(eye_grey, eye_grey_small, Size(0,0), scale, scale); // resizing to width to 40 while keeping the WxH ratio the same.
    } else{
        eye_grey_small  = eye_grey;
    }

    GaussianBlur(eye_grey,eye_grey,Size(5,5),0);

    // Create centermap
    Mat centermap = get_centermap(eye_grey_small);

    // Find position of max value in small-size centermap
    Point maxLoc;
    minMaxLoc(centermap, NULL, NULL, NULL, &maxLoc);

    // Return re-scaled center to full size
    return maxLoc * (1/scale);
}

extern "C" {
JNIEXPORT jfloatArray JNICALL
Java_com_google_android_gms_samples_vision_face_googlyeyes_GooglyEyesActivity_rightEyeFromJNI(
        JNIEnv *jniEnv,
        jobject thiz/* this */, jbyteArray s_yuv, jfloat rightX, jfloat rightY,  jint height, jint width, jfloat distanceBetweenEyes) {

    jbyte* _s_yuv = jniEnv->GetByteArrayElements(s_yuv, 0);

    // This gives us the grayscale image without any conversion
    Mat _srcImg = Mat(height ,width, CV_8UC1, (unsigned char*) _s_yuv);
    // Mobile Vision detector rotates the frames 270 degree counterclockwise. This is to compensate.
    rotate(_srcImg, _srcImg, ROTATE_90_COUNTERCLOCKWISE);

    int eyeWidth = EYE_ROI_PROPORTION_WIDTH * distanceBetweenEyes;
    int eyeHeight = EYE_ROI_PROPORTION_HEIGHT * distanceBetweenEyes;
    Rect eyeROI = Rect(int(rightX) - (eyeWidth/2), int(rightY) - (eyeHeight*3)/5, eyeWidth, eyeHeight);

    float coordX, coordY;
    // checks the box is inside the image region
    if (0 <= eyeROI.x && 0 <= eyeROI.width && eyeROI.x + eyeROI.width <= _srcImg.cols
        && 0 <= eyeROI.y && 0 <= eyeROI.height && eyeROI.y + eyeROI.height <= _srcImg.rows){

       equalizeHist(_srcImg(eyeROI), _srcImg(eyeROI));
        erase_specular(_srcImg(eyeROI));

//      runs the algorithm
        Point temp = find_eye_center(_srcImg(eyeROI));

        if(temp.x  <= 1 || temp.y <= 1 || temp.x  >= eyeWidth -1 || temp.y >= eyeHeight -1){
            //this means the detection failed. Sending (0,0) makes sure that the last detected position is used instead.
            coordX = 0.0f;
            coordY = 0.0f;
        } else{
            coordX = temp.x + eyeROI.tl().x;
            coordY = temp.y + eyeROI.tl().y;
        }

    } else{ // only to show that there is something wrong
        coordX = 30.0F; coordY = 30.0F;
    }
    jfloat arrayDummy[] = {coordX, coordY };

    jfloatArray result = jniEnv->NewFloatArray(2);
    jniEnv->SetFloatArrayRegion (result, 0, 2, arrayDummy);
    jniEnv->ReleaseByteArrayElements(s_yuv, _s_yuv, 0);
    return result;
}
}

extern "C" {
JNIEXPORT jfloatArray JNICALL
Java_com_google_android_gms_samples_vision_face_googlyeyes_GooglyEyesActivity_leftEyeFromJNI(
        JNIEnv *jniEnv,
        jobject thiz/* this */, jbyteArray s_yuv, jfloat leftX, jfloat leftY, jint height, jint width, jfloat distanceBetweenEyes) {

    jbyte* _s_yuv = jniEnv->GetByteArrayElements(s_yuv, 0);

    // This gives us the grayscale image without any conversion
    Mat _srcImg = Mat(height ,width, CV_8UC1, (unsigned char*) _s_yuv);
    // Mobile Vision detector rotates 270 degree. This is to compensate.
    rotate(_srcImg, _srcImg, ROTATE_90_COUNTERCLOCKWISE);

    int eyeWidth = EYE_ROI_PROPORTION_WIDTH * distanceBetweenEyes;
    int eyeHeight = EYE_ROI_PROPORTION_HEIGHT * distanceBetweenEyes;
    Rect eyeROI = Rect(int(leftX) - (eyeWidth/2), int(leftY) - (eyeHeight*3)/5, eyeWidth, eyeHeight);

    float coordX, coordY;
    // checks the box is inside the image region
    if (0 <= eyeROI.x && 0 <= eyeROI.width && eyeROI.x + eyeROI.width <= _srcImg.cols
        && 0 <= eyeROI.y && 0 <= eyeROI.height && eyeROI.y + eyeROI.height <= _srcImg.rows){

        equalizeHist(_srcImg(eyeROI), _srcImg(eyeROI));
        erase_specular(_srcImg(eyeROI));

        // runs the algorithm
        Point temp = find_eye_center(_srcImg(eyeROI));

        if(temp.x  <= 1 || temp.y <= 1 || temp.x  >= eyeWidth -1 || temp.y >= eyeHeight -1){
            //this means the detection failed. Sending (0,0) makes sure that the last detected position is used instead.
            coordX = 0.0f;
            coordY = 0.0f;
        } else{
            coordX = temp.x + eyeROI.tl().x;
            coordY = temp.y + eyeROI.tl().y;
        }

    } else{ // only to show that there is something wrong
        coordX = 50.0F; coordY = 50.0F;
    }
    jfloat arrayDummy[] = {coordX, coordY };

    jfloatArray result = jniEnv->NewFloatArray(2);
    jniEnv->SetFloatArrayRegion (result, 0, 2, arrayDummy);
    jniEnv->ReleaseByteArrayElements(s_yuv, _s_yuv, 0);
    return result;
}

}
extern "C"
JNIEXPORT jstring JNICALL
Java_com_google_android_gms_samples_vision_face_googlyeyes_GooglyEyesActivity_stringFromJNI(
        JNIEnv *env,
        jobject /* this */) {
    string hello = "Hello from C++";
    sleep(1);
    return env->NewStringUTF(hello.c_str());
}



