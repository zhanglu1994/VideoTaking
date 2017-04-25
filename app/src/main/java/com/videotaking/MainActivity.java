package com.videotaking;

import android.app.Activity;
import android.content.Intent;
import android.hardware.Camera;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AlertDialog;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.yixia.camera.MediaRecorderNative;
import com.yixia.camera.VCamera;
import com.yixia.camera.model.MediaObject;
import com.yixia.videoeditor.adapter.UtilityAdapter;

import java.util.LinkedList;

public class MainActivity extends Activity implements View.OnClickListener {

    private static final int REQUEST_KEY = 100;
    private static final int HANDLER_RECORD = 200;
    private static final int HANDLER_EDIT_VIDEO = 201;

    private MediaRecorderNative mMediaRecorder;
    private MediaObject mMediaObject;
    private FocusSurfaceView sv_ffmpeg;
    private RecordedButton rb_start;
    private RelativeLayout rl_bottom;
    private RelativeLayout rl_bottom2;
    private ImageView iv_back;
    private TextView tv_hint;
    private TextView textView;
    private MyVideoView vv_play;

    //最大录制时间
    private int maxDuration = 10000;
    //本次段落是否录制完成
    private boolean isRecordedOver;


    private TextView tv_changeCamera;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        setContentView(R.layout.activity_main);

        sv_ffmpeg = (FocusSurfaceView) findViewById(R.id.sv_ffmpeg);
        rb_start = (RecordedButton) findViewById(R.id.rb_start);
        vv_play = (MyVideoView) findViewById(R.id.vv_play);
        ImageView iv_finish = (ImageView) findViewById(R.id.iv_finish);
        iv_back = (ImageView) findViewById(R.id.iv_back);
        tv_hint = (TextView) findViewById(R.id.tv_hint);
        rl_bottom = (RelativeLayout) findViewById(R.id.rl_bottom);
        rl_bottom2 = (RelativeLayout) findViewById(R.id.rl_bottom2);
        ImageView iv_next = (ImageView) findViewById(R.id.iv_next);
        ImageView iv_close = (ImageView) findViewById(R.id.iv_close);

        tv_changeCamera = (TextView) findViewById(R.id.tv_changeCamera);
        tv_changeCamera.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mMediaRecorder.getCamerId() == Camera.CameraInfo.CAMERA_FACING_BACK){
                    mMediaRecorder.stopPreview();
                    mMediaRecorder.setCameraId(Camera.CameraInfo.CAMERA_FACING_FRONT);
                    mMediaRecorder.startPreview();
                }else if (mMediaRecorder.getCamerId() == Camera.CameraInfo.CAMERA_FACING_FRONT){
                    mMediaRecorder.stopPreview();
                    mMediaRecorder.setCameraId(Camera.CameraInfo.CAMERA_FACING_BACK);
                    mMediaRecorder.startPreview();
                }
            }
        });

        initMediaRecorder();
        sv_ffmpeg.setTouchFocus(mMediaRecorder);            //对焦
        rb_start.setMax(maxDuration);                       //设置录制时长
        rb_start.setOnGestureListener(new RecordedButton.OnGestureListener() {
            @Override
            public void onLongClick() {
                isRecordedOver = false;
                mMediaRecorder.startRecord();
                rb_start.setSplit();
                myHandler.sendEmptyMessageDelayed(HANDLER_RECORD, 100);
            }

            @Override
            public void onClick() {

            }

            @Override
            public void onLift() {
                isRecordedOver = true;
                mMediaRecorder.stopRecord();
                changeButton(mMediaObject.getMediaParts().size() > 0);
            }

            @Override
            public void onOver() {
                isRecordedOver = true;
                rb_start.closeButton();
                mMediaRecorder.stopRecord();
                videoFinish();
            }
        });

        iv_back.setOnClickListener(this);
        iv_finish.setOnClickListener(this);
        iv_next.setOnClickListener(this);
        iv_close.setOnClickListener(this);

    }


    @Override
    public void onClick(View v) {
        switch (v.getId()){
            case R.id.iv_back:
                if(rb_start.isDeleteMode()){//判断是否要删除视频段落
                    MediaObject.MediaPart lastPart = mMediaObject.getPart(mMediaObject.getMediaParts().size() - 1);
                    mMediaObject.removePart(lastPart, true);
                    rb_start.setProgress(mMediaObject.getDuration());
                    rb_start.deleteSplit();
                    changeButton(mMediaObject.getMediaParts().size() > 0);
                    iv_back.setImageResource(R.mipmap.video_delete);
                }else if(mMediaObject.getMediaParts().size() > 0){
                    rb_start.setDeleteMode(true);
                    iv_back.setImageResource(R.mipmap.video_delete_click);
                }

                break;

            case R.id.iv_finish:
                videoFinish();
                break;

            case R.id.iv_next:
                rb_start.setDeleteMode(false);
                Intent intent = new Intent(MainActivity.this, EditVideoActivity.class);
                intent.putExtra("path", MyApplication.VIDEO_PATH+"/finish.mp4");
                startActivityForResult(intent, REQUEST_KEY);
                break;

            case R.id.iv_close:
                initMediaRecorderState();
                break;
        }

    }

    @Override
    public void onBackPressed() {
        super.onBackPressed();
        if(rb_start.getSplitCount() == 0) {
            super.onBackPressed();
        }else{
            initMediaRecorderState();
        }
    }

    /**
     * 初始化视频拍摄状态
     */
    private void initMediaRecorderState(){

        vv_play.setVisibility(View.GONE);
        vv_play.pause();

        rb_start.setVisibility(View.VISIBLE);
        rl_bottom2.setVisibility(View.GONE);
        changeButton(false);
        tv_hint.setVisibility(View.VISIBLE);

        LinkedList<MediaObject.MediaPart> list = new LinkedList<>();
        list.addAll(mMediaObject.getMediaParts());

        for (MediaObject.MediaPart part : list){
            mMediaObject.removePart(part, true);
        }

        rb_start.setProgress(mMediaObject.getDuration());
        rb_start.cleanSplit();
    }

    /**
     * 初始化录制对象
     */
    private void initMediaRecorder() {

        mMediaRecorder = new MediaRecorderNative();
        String key = String.valueOf(System.currentTimeMillis());
        //设置缓存文件夹
        mMediaObject = mMediaRecorder.setOutputDirectory(key, VCamera.getVideoCachePath());
        //设置视频预览源
        mMediaRecorder.setSurfaceHolder(sv_ffmpeg.getHolder());

        //准备
        mMediaRecorder.prepare();
        //滤波器相关
        UtilityAdapter.freeFilterParser();
        UtilityAdapter.initFilterParser();

    }

    private void changeButton(boolean flag){

        if(flag){
            tv_hint.setVisibility(View.VISIBLE);
            rl_bottom.setVisibility(View.VISIBLE);
        }else{
            tv_hint.setVisibility(View.GONE);
            rl_bottom.setVisibility(View.GONE);
        }
    }



    private Handler myHandler = new Handler(){
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what){
                case HANDLER_RECORD://拍摄视频的handler
                    if(!isRecordedOver){
                        if(rl_bottom.getVisibility() == View.VISIBLE) {
                            changeButton(false);
                        }
                        rb_start.setProgress(mMediaObject.getDuration());
                        myHandler.sendEmptyMessageDelayed(HANDLER_RECORD, 30);
                    }
                    break;
                case HANDLER_EDIT_VIDEO://合成视频的handler
                    int progress = UtilityAdapter.FilterParserAction("", UtilityAdapter.PARSERACTION_PROGRESS);
                    if(textView != null) textView.setText("视频编译中 "+progress+"%");
                    if (progress == 100) {
                        syntVideo();
                    } else if (progress == -1) {
                        closeProgressDialog();
                        Toast.makeText(getApplicationContext(), "视频合成失败", Toast.LENGTH_SHORT).show();
                    } else {
                        sendEmptyMessageDelayed(HANDLER_EDIT_VIDEO, 30);
                    }
                    break;
            }
        }
    };

    /**
     * 合成视频
     */
    private void syntVideo(){

        //ffmpeg -i "concat:ts0.ts|ts1.ts|ts2.ts|ts3.ts" -c copy -bsf:a aac_adtstoasc out2.mp4
        StringBuilder sb = new StringBuilder("ffmpeg");
        sb.append(" -i");
        String concat="concat:";
        for (MediaObject.MediaPart part : mMediaObject.getMediaParts()){
            concat+=part.mediaPath;
            concat += "|";
        }
        concat = concat.substring(0, concat.length()-1);
        sb.append(" "+concat);
        sb.append(" -c");
        sb.append(" copy");
        sb.append(" -bsf:a");
        sb.append(" aac_adtstoasc");
        sb.append(" -y");
        String output = MyApplication.VIDEO_PATH+"/finish.mp4";
        sb.append(" "+output);

        int i = UtilityAdapter.FFmpegRun("", sb.toString());
        closeProgressDialog();
        if(i == 0){
            rl_bottom2.setVisibility(View.VISIBLE);
            vv_play.setVisibility(View.VISIBLE);

            vv_play.setVideoPath(output);
            vv_play.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                @Override
                public void onPrepared(MediaPlayer mp) {
                    vv_play.setLooping(true);
                    vv_play.start();
                }
            });
            if(vv_play.isPrepared()){
                vv_play.setLooping(true);
                vv_play.start();
            }
        }else{
            Toast.makeText(getApplicationContext(), "视频合成失败", Toast.LENGTH_SHORT).show();
        }
    }

    private void videoFinish() {

        changeButton(false);
        rb_start.setVisibility(View.GONE);

        textView = showProgressDialog();

        myHandler.sendEmptyMessage(HANDLER_EDIT_VIDEO);
    }


    @Override
    protected void onResume() {
        super.onResume();
        mMediaRecorder.startPreview();
    }

    @Override
    protected void onPause() {
        super.onPause();
        mMediaRecorder.stopPreview();
    }


    @Override
    protected void onDestroy() {
        super.onDestroy();
        mMediaObject.cleanTheme();
        mMediaRecorder.release();
    }

    public void closeProgressDialog() {
        try {
            if (progressDialog != null) {
                progressDialog.dismiss();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private AlertDialog progressDialog;

    public TextView showProgressDialog() {

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setCancelable(false);
        View view = View.inflate(this, R.layout.dialog_loading, null);
        builder.setView(view);
        ProgressBar pb_loading = (ProgressBar) view.findViewById(R.id.pb_loading);
        TextView tv_hint = (TextView) view.findViewById(R.id.tv_hint);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            pb_loading.setIndeterminateTintList(ContextCompat.getColorStateList(this, R.color.dialog_pro_color));
        }
        tv_hint.setText("视频编译中");
        progressDialog = builder.create();
        progressDialog.show();

        return tv_hint;
    }


}
