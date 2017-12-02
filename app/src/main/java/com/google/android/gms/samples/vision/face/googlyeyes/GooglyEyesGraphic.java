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

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.Paint;
import android.graphics.PointF;

import com.google.android.gms.samples.vision.face.googlyeyes.ui.camera.GraphicOverlay;

import java.util.Timer;

/**
 * Graphics class for rendering Googly Eyes on a graphic overlay given the current eye positions.
 */
class GooglyEyesGraphic extends GraphicOverlay.Graphic {
    private static final float EYE_ROI_PROPORTION_WIDTH = 0.40f;
    private static final float EYE_ROI_PROPORTION_HEIGHT = 0.30f;

    private Paint mEyeIrisPaint;
    private Paint mEyeOutlinePaint;
    private Paint mEyeLidPaint;


    public volatile PointF mLeftPosition;
    private volatile boolean mLeftOpen;

    public volatile PointF mRightPosition;
    private volatile boolean mRightOpen;

    public volatile PointF mLeftPupil;
    public volatile PointF mRightPupil;

    //==============================================================================================
    // Methods
    //==============================================================================================

    GooglyEyesGraphic(GraphicOverlay overlay) {
        super(overlay);

        mEyeLidPaint = new Paint();
        mEyeLidPaint.setColor(Color.BLUE);
        mEyeLidPaint.setStyle(Paint.Style.FILL);

        mEyeIrisPaint = new Paint();
        mEyeIrisPaint.setColor(Color.WHITE);
        mEyeIrisPaint.setStyle(Paint.Style.FILL);

        mEyeOutlinePaint = new Paint();
        mEyeOutlinePaint.setColor(Color.BLACK);
        mEyeOutlinePaint.setStyle(Paint.Style.STROKE);
        mEyeOutlinePaint.setStrokeWidth(5);

    }


    /**
     * Updates the eye positions and state from the detection of the most recent frame.  Invalidates
     * the relevant portions of the overlay to trigger a redraw.
     */
    void updateEyes(PointF leftPosition, boolean leftOpen,
                    PointF rightPosition, boolean rightOpen) {
        mLeftPosition = leftPosition;
        mLeftOpen = leftOpen;

        mRightPosition = rightPosition;
        mRightOpen = rightOpen;

       postInvalidate();

    }

    void updateLeftPupil(PointF leftPupil){
        mLeftPupil = leftPupil;
    }
    void updateRightPupil(PointF rightPupil){
        mRightPupil = rightPupil;
    }

    /**
     * Draws the current eye state to the supplied canvas.  This will draw the eyes at the last
     * reported position from the tracker.
     */
    @Override
    public void draw(Canvas canvas) {
        PointF detectLeftPosition = mLeftPosition;
        PointF detectRightPosition = mRightPosition;
        if ((detectLeftPosition == null) || (detectRightPosition == null)) {
            return;
        }

        PointF leftPosition =
                new PointF(translateX(detectLeftPosition.x), translateY(detectLeftPosition.y));
        PointF rightPosition =
                new PointF(translateX(detectRightPosition.x), translateY(detectRightPosition.y));

        if(mLeftPupil != null) {
            PointF leftPupilPosition =
                    new PointF(translateX(mLeftPupil.x), translateY(mLeftPupil.y));
            drawPupil(canvas, leftPupilPosition);
        }

        if(mRightPupil != null) {
            PointF rightPupilPosition =
                    new PointF(translateX(mRightPupil.x), translateY(mRightPupil.y));
            drawPupil(canvas, rightPupilPosition);
        }

        // Use the inter-eye distance to set the size of the eyes.
        float distance = (float) Math.sqrt(
                Math.pow(rightPosition.x - leftPosition.x, 2) +
                Math.pow(rightPosition.y - leftPosition.y, 2));
        float eyeWidth = EYE_ROI_PROPORTION_WIDTH * distance;
        float eyeHeight = EYE_ROI_PROPORTION_HEIGHT * distance;

        // Draw left eye.
        drawEye(canvas, leftPosition, eyeWidth, eyeHeight);

        // Draw right eye.
        drawEye(canvas, rightPosition, eyeWidth, eyeHeight);

    }

    /**
     * Draws the eye.
     */
    private void drawEye(Canvas canvas, PointF eyePosition, float eyeWidth,
                          float eyeHeight) {
        if (mLeftOpen || mRightOpen) {

        } else {
            // Blue circle to show it is closed.
            canvas.drawCircle(eyePosition.x, eyePosition.y, eyeWidth, mEyeLidPaint);
        }


        canvas.drawRect(eyePosition.x - eyeWidth/2, eyePosition.y - (eyeHeight*3)/5 ,eyePosition.x + eyeWidth/2, eyePosition.y + (eyeHeight*2)/5 , mEyeOutlinePaint);
    }
    private void drawPupil(Canvas canvas, PointF pupilPosition){

        canvas.drawCircle(pupilPosition.x, pupilPosition.y, 9, mEyeIrisPaint );
    }

}
