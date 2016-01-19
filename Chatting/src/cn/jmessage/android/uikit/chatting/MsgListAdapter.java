package cn.jmessage.android.uikit.chatting;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.AnimationDrawable;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.MediaPlayer.OnErrorListener;
import android.media.MediaPlayer.OnPreparedListener;
import android.os.Build;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.View.OnLongClickListener;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.view.animation.LinearInterpolator;
import android.widget.BaseAdapter;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import cn.jmessage.android.uikit.chatting.utils.IdHelper;
import cn.jpush.im.android.api.callback.GetAvatarBitmapCallback;
import cn.jpush.im.android.api.content.CustomContent;
import cn.jpush.im.android.api.content.EventNotificationContent;
import cn.jpush.im.android.api.enums.MessageStatus;
import cn.jpush.im.android.api.model.GroupInfo;
import cn.jpush.im.android.api.model.UserInfo;

import cn.jmessage.android.uikit.chatting.utils.DialogCreator;
import cn.jmessage.android.uikit.chatting.utils.FileHelper;
import cn.jmessage.android.uikit.chatting.utils.HandleResponseCode;
import cn.jmessage.android.uikit.chatting.utils.TimeFormat;

import com.squareup.picasso.Picasso;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import cn.jpush.im.android.api.model.Conversation;
import cn.jpush.im.android.api.JMessageClient;
import cn.jpush.im.android.api.model.Message;
import cn.jpush.im.android.api.callback.DownloadCompletionCallback;
import cn.jpush.im.android.api.callback.ProgressUpdateCallback;
import cn.jpush.im.android.api.content.ImageContent;
import cn.jpush.im.android.api.content.TextContent;
import cn.jpush.im.android.api.content.VoiceContent;
import cn.jpush.im.android.api.enums.ContentType;
import cn.jpush.im.android.api.enums.MessageDirect;
import cn.jpush.im.api.BasicCallback;

@SuppressLint("NewApi")
public class MsgListAdapter extends BaseAdapter {

    private static final String TAG = "MsgListAdapter";
    public static final int PAGE_MESSAGE_COUNT = 18;
    private Context mContext;
    private String mTargetId;
    private Conversation mConv;
    private List<Message> mMsgList = new ArrayList<Message>();//所有消息列表
    private List<Integer> mIndexList = new ArrayList<Integer>();//语音索引
    private LayoutInflater mInflater;
    private boolean mSetData = false;
    private boolean mIsGroup = false;
    private long mGroupId;
    private int mPosition = -1;// 和mSetData一起组成判断播放哪条录音的依据
    // 7种Item的类型
    // 文本
    private final int TYPE_RECEIVE_TXT = 0;
    private final int TYPE_SEND_TXT = 1;
    // 图片
    private final int TYPE_SEND_IMAGE = 2;
    private final int TYPE_RECEIVER_IMAGE = 3;
    // 语音
    private final int TYPE_SEND_VOICE = 4;
    private final int TYPE_RECEIVER_VOICE = 5;
    //自定义消息
    private final int TYPE_CUSTOM_TXT = 6;
    private final MediaPlayer mp = new MediaPlayer();
    private AnimationDrawable mVoiceAnimation;
    private FileInputStream mFIS;
    private FileDescriptor mFD;
    private Activity mActivity;
    private boolean autoPlay = false;
    private int nextPlayPosition = 0;
    private boolean mIsEarPhoneOn;
    //当前第0项消息的位置
    private int mStart;
    //上一页的消息数
    private int mOffset = PAGE_MESSAGE_COUNT;
    private boolean mHasLastPage = false;
    private Dialog mDialog;
    //发送图片消息的队列
    private Queue<Message> mMsgQueue = new LinkedList<Message>();
    private int mSendMsgId;
    private float mDensity;
    private int mWidth;
    private Animation mSendingAnim;

    public MsgListAdapter(Context context, String targetId) {
        initData(context);
        this.mTargetId = targetId;
        this.mConv = JMessageClient.getSingleConversation(mTargetId);
        this.mMsgList = mConv.getMessagesFromNewest(0, mOffset);
        reverse(mMsgList);
        mStart = mOffset;
        UserInfo userInfo = (UserInfo)mConv.getTargetInfo();
        if (!TextUtils.isEmpty(userInfo.getAvatar())){
            userInfo.getAvatarBitmap(new GetAvatarBitmapCallback() {
                @Override
                public void gotResult(int status, String desc, Bitmap bitmap) {
                    if (status == 0) {
                        notifyDataSetChanged();
                    } else {
                        HandleResponseCode.onHandle(mContext, status, false);
                    }
                }
            });
        }
        checkSendingImgMsg();
    }

    public MsgListAdapter(Context context, long groupId) {
        initData(context);
        this.mGroupId = groupId;
        this.mIsGroup = true;
        this.mConv = JMessageClient.getGroupConversation(mGroupId);
        this.mMsgList = mConv.getMessagesFromNewest(0, mOffset);
        reverse(mMsgList);
        mStart = mOffset;
        checkSendingImgMsg();
    }

    private void reverse(List<Message> list) {
        if (list != null) {
            Collections.reverse(list);
        }
    }

    private void initData(Context context) {
        this.mContext = context;
        mActivity = (Activity) context;
        DisplayMetrics dm = new DisplayMetrics();
        mActivity.getWindowManager().getDefaultDisplay().getMetrics(dm);
        mDensity = dm.density;
        mWidth = dm.widthPixels;

        mSendingAnim = AnimationUtils.loadAnimation(mContext, IdHelper.getAnim(mContext, "jmui_rotate"));
        LinearInterpolator lin = new LinearInterpolator();
        mSendingAnim.setInterpolator(lin);

        mInflater = LayoutInflater.from(mContext);
        AudioManager audioManager = (AudioManager) mContext
                .getSystemService(Context.AUDIO_SERVICE);
        audioManager.setMode(AudioManager.MODE_NORMAL);
        if (audioManager.isSpeakerphoneOn()) {
            audioManager.setSpeakerphoneOn(true);
        } else {
            audioManager.setSpeakerphoneOn(false);
        }
        mp.setAudioStreamType(AudioManager.STREAM_RING);
        mp.setOnErrorListener(new OnErrorListener() {

            @Override
            public boolean onError(MediaPlayer mp, int what, int extra) {
                return false;
            }
        });
    }

    public void dropDownToRefresh() {
        if (mConv != null) {
            List<Message> msgList = mConv.getMessagesFromNewest(mStart, PAGE_MESSAGE_COUNT);
            if (msgList != null) {
                for (Message msg : msgList) {
                    mMsgList.add(0, msg);
                }
                if (msgList.size() > 0) {
                    checkSendingImgMsg();
                    mOffset = msgList.size();
                    mHasLastPage = true;
                } else {
                    mOffset = 0;
                    mHasLastPage = false;
                }
                notifyDataSetChanged();
            }
        }
    }

    public int getOffset() {
        return mOffset;
    }

    public boolean isHasLastPage() {
        return mHasLastPage;
    }

    public void refreshStartPosition() {
        mStart += mOffset;
    }

    //当有新消息加到MsgList，自增mStart
    private void incrementStartPosition() {
        ++mStart;
    }

    public void initMediaPlayer() {
        mp.reset();
    }

    /**
     * 检查图片是否处于创建状态，如果是，则加入发送队列
     */
    private void checkSendingImgMsg() {
        if (mMsgList != null && mMsgList.size() > 0) {
            for (Message msg : mMsgList) {
                if (msg.getStatus() == MessageStatus.created
                        && msg.getContentType() == ContentType.image) {
                    mMsgQueue.offer(msg);
                }
            }
        }
    }

    //发送图片 将图片加入发送队列
    public void setSendImg(String targetId, int[] msgIds) {
        Message msg;
        mConv = JMessageClient.getSingleConversation(targetId);
        for (int msgId : msgIds) {
            msg = mConv.getMessage(msgId);
            if (msg != null) {
                mMsgList.add(msg);
                incrementStartPosition();
                mMsgQueue.offer(msg);
            }
        }

        Message message = mMsgQueue.element();
        sendNextImgMsg(message);
        notifyDataSetChanged();
    }

    public void setSendImg(long groupId, int[] msgIds) {
        Message msg;
        mConv = JMessageClient.getGroupConversation(groupId);
        for (int msgId : msgIds) {
            msg = mConv.getMessage(msgId);
            if (msg != null) {
                mMsgList.add(msg);
                incrementStartPosition();
                mMsgQueue.offer(msg);
            }
        }

        Message message = mMsgQueue.element();
        sendNextImgMsg(message);
        notifyDataSetChanged();

    }


    /**
     * 从发送队列中出列，并发送图片
     * @param msg 图片消息
     */
    private void sendNextImgMsg(Message msg) {
        JMessageClient.sendMessage(msg);
        msg.setOnSendCompleteCallback(new BasicCallback() {
            @Override
            public void gotResult(int i, String s) {
                //出列
                mMsgQueue.poll();
                //如果队列不为空，则继续发送下一张
                if (!mMsgQueue.isEmpty()) {
                    sendNextImgMsg(mMsgQueue.element());
                }
                notifyDataSetChanged();
            }
        });
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    public void releaseMediaPlayer() {
        if (mp != null)
            mp.release();
    }

    public void addMsgToList(Message msg) {
        mMsgList.add(msg);
        incrementStartPosition();
        mActivity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                notifyDataSetChanged();
            }
        });
    }

    public Message getLastMsg() {
        if (mMsgList.size() > 0) {
            return mMsgList.get(mMsgList.size() - 1);
        } else {
            return null;
        }
    }

    @Override
    public int getCount() {
        return mMsgList.size();
    }

    @Override
    public int getItemViewType(int position) {
        Message msg = mMsgList.get(position);
        //是文字类型或者自定义类型（用来显示群成员变化消息）
        if (msg.getContentType() == ContentType.text) {
            return msg.getDirect() == MessageDirect.send ? TYPE_SEND_TXT
                    : TYPE_RECEIVE_TXT;
        } else if (msg.getContentType() == ContentType.image) {
            return msg.getDirect() == MessageDirect.send ? TYPE_SEND_IMAGE
                    : TYPE_RECEIVER_IMAGE;
        } else if (msg.getContentType() == ContentType.voice) {
            return msg.getDirect() == MessageDirect.send ? TYPE_SEND_VOICE
                    : TYPE_RECEIVER_VOICE;
        } else {
            return TYPE_CUSTOM_TXT;
        }
    }

    public int getViewTypeCount() {
        return 11;
    }

    private View createViewByType(Message msg, int position) {
        // 会话类型
        switch (msg.getContentType()) {
            case image:
                return getItemViewType(position) == TYPE_SEND_IMAGE ? mInflater
                        .inflate(IdHelper.getLayout(mContext, "jmui_chat_item_send_image"), null) : mInflater
                        .inflate(IdHelper.getLayout(mContext, "jmui_chat_item_receive_image"), null);
            case voice:
                return getItemViewType(position) == TYPE_SEND_VOICE ? mInflater
                        .inflate(IdHelper.getLayout(mContext, "jmui_chat_item_send_voice"), null) : mInflater
                        .inflate(IdHelper.getLayout(mContext, "jmui_chat_item_receive_voice"), null);
            case text:
                return getItemViewType(position) == TYPE_SEND_TXT ? mInflater
                        .inflate(IdHelper.getLayout(mContext, "jmui_chat_item_send_text"), null) : mInflater
                        .inflate(IdHelper.getLayout(mContext, "jmui_chat_item_receive_text"), null);
            default:
                return mInflater.inflate(IdHelper.getLayout(mContext, "jmui_chat_item_group_change"), null);
        }
    }

    @Override
    public Message getItem(int position) {
        return mMsgList.get(position);
    }

    public void setAudioPlayByEarPhone(int state) {
        AudioManager audioManager = (AudioManager) mContext
                .getSystemService(Context.AUDIO_SERVICE);
        int currVolume = audioManager.getStreamVolume(AudioManager.STREAM_VOICE_CALL);
        audioManager.setMode(AudioManager.MODE_IN_CALL);
        if (state == 0) {
            mIsEarPhoneOn = false;
            audioManager.setSpeakerphoneOn(true);
            audioManager.setStreamVolume(AudioManager.STREAM_VOICE_CALL,
                    audioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL),
                    AudioManager.STREAM_VOICE_CALL);
            Log.i(TAG, "set SpeakerphoneOn true!");
        } else {
            mIsEarPhoneOn = true;
            audioManager.setSpeakerphoneOn(false);
            audioManager.setStreamVolume(AudioManager.STREAM_VOICE_CALL, currVolume,
                    AudioManager.STREAM_VOICE_CALL);
            Log.i(TAG, "set SpeakerphoneOn false!");
        }
    }

    public void clearMsgList() {
        mMsgList.clear();
        mStart = 0;
        notifyDataSetChanged();
    }

    @Override
    public View getView(final int position, View convertView, ViewGroup parent) {
        final Message msg = mMsgList.get(position);
        final UserInfo userInfo = msg.getFromUser();
        final ViewHolder holder;
        if (convertView == null) {
            holder = new ViewHolder();
            convertView = createViewByType(msg, position);
            switch (msg.getContentType()) {
                case text:
                    holder.headIcon = (CircleImageView) convertView
                            .findViewById(IdHelper.getViewID(mContext, "jmui_avatar_iv"));
                    holder.displayName = (TextView) convertView
                            .findViewById(IdHelper.getViewID(mContext, "jmui_display_name_tv"));
                    holder.txtContent = (TextView) convertView
                            .findViewById(IdHelper.getViewID(mContext, "jmui_msg_content"));
                    holder.sendingIv = (ImageView) convertView
                            .findViewById(IdHelper.getViewID(mContext, "jmui_sending_iv"));
                    holder.resend = (ImageButton) convertView
                            .findViewById(IdHelper.getViewID(mContext, "jmui_fail_resend_ib"));
                    holder.groupChange = (TextView) convertView
                            .findViewById(IdHelper.getViewID(mContext, "jmui_group_content"));
                    break;
                case image:
                    holder.headIcon = (CircleImageView) convertView
                            .findViewById(IdHelper.getViewID(mContext, "jmui_avatar_iv"));
                    holder.displayName = (TextView) convertView
                            .findViewById(IdHelper.getViewID(mContext, "jmui_display_name_tv"));
                    holder.picture = (ImageView) convertView
                            .findViewById(IdHelper.getViewID(mContext, "jmui_picture_iv"));
                    holder.sendingIv = (ImageView) convertView
                            .findViewById(IdHelper.getViewID(mContext, "jmui_sending_iv"));
                    holder.progressTv = (TextView) convertView
                            .findViewById((IdHelper.getViewID(mContext, "jmui_progress_tv")));
                    holder.resend = (ImageButton) convertView
                            .findViewById(IdHelper.getViewID(mContext, "jmui_fail_resend_ib"));
                    break;
                case voice:
                    holder.headIcon = (CircleImageView) convertView
                            .findViewById(IdHelper.getViewID(mContext, "jmui_avatar_iv"));
                    holder.displayName = (TextView) convertView
                            .findViewById(IdHelper.getViewID(mContext, "jmui_display_name_tv"));
                    holder.txtContent = (TextView) convertView
                            .findViewById(IdHelper.getViewID(mContext, "jmui_msg_content"));
                    holder.voice = ((ImageView) convertView
                            .findViewById(IdHelper.getViewID(mContext, "jmui_voice_iv")));
                    holder.sendingIv = (ImageView) convertView
                            .findViewById(IdHelper.getViewID(mContext, "jmui_sending_iv"));
                    holder.voiceLength = (TextView) convertView
                            .findViewById(IdHelper.getViewID(mContext, "jmui_voice_length_tv"));
                    holder.readStatus = (ImageView) convertView
                            .findViewById(IdHelper.getViewID(mContext, "jmui_read_status_iv"));
                    holder.resend = (ImageButton) convertView
                            .findViewById(IdHelper.getViewID(mContext, "jmui_fail_resend_ib"));
                    break;
                case location:
                    holder.headIcon = (CircleImageView) convertView
                            .findViewById(IdHelper.getViewID(mContext, "jmui_avatar_iv"));
                    holder.displayName = (TextView) convertView
                            .findViewById(IdHelper.getViewID(mContext, "jmui_display_name_tv"));
                    holder.txtContent = (TextView) convertView
                            .findViewById(IdHelper.getViewID(mContext, "jmui_msg_content"));
                    holder.sendingIv = (ImageView) convertView
                            .findViewById(IdHelper.getViewID(mContext, "jmui_sending_iv"));
                    holder.resend = (ImageButton) convertView
                            .findViewById(IdHelper.getViewID(mContext, "jmui_fail_resend_ib"));
                    break;
                case eventNotification:
                    holder.groupChange = (TextView) convertView
                            .findViewById(IdHelper.getViewID(mContext, "jmui_group_content"));
                    break;
                default:
                    holder.groupChange = (TextView) convertView
                            .findViewById(IdHelper.getViewID(mContext, "jmui_group_content"));
            }
            convertView.setTag(holder);
        } else {
            holder = (ViewHolder) convertView.getTag();
        }

        //显示时间
        TextView msgTime = (TextView) convertView
                .findViewById(IdHelper.getViewID(mContext, "jmui_send_time_txt"));
        long nowDate = msg.getCreateTime();
        if (mOffset == 18) {
            if (position == 0 || position % 18 == 0) {
                TimeFormat timeFormat = new TimeFormat(mContext, nowDate);
                msgTime.setText(timeFormat.getDetailTime());
                msgTime.setVisibility(View.VISIBLE);
            } else {
                long lastDate = mMsgList.get(position - 1).getCreateTime();
                // 如果两条消息之间的间隔超过十分钟则显示时间
                if (nowDate - lastDate > 600000) {
                    TimeFormat timeFormat = new TimeFormat(mContext, nowDate);
                    msgTime.setText(timeFormat.getDetailTime());
                    msgTime.setVisibility(View.VISIBLE);
                } else {
                    msgTime.setVisibility(View.GONE);
                }
            }
        } else {
            if (position == 0 || position == mOffset
                    || (position - mOffset) % 18 == 0) {
                TimeFormat timeFormat = new TimeFormat(mContext, nowDate);

                msgTime.setText(timeFormat.getDetailTime());
                msgTime.setVisibility(View.VISIBLE);
            } else {
                long lastDate = mMsgList.get(position - 1).getCreateTime();
                // 如果两条消息之间的间隔超过十分钟则显示时间
                if (nowDate - lastDate > 600000) {
                    TimeFormat timeFormat = new TimeFormat(mContext, nowDate);
                    msgTime.setText(timeFormat.getDetailTime());
                    msgTime.setVisibility(View.VISIBLE);
                } else {
                    msgTime.setVisibility(View.GONE);
                }
            }
        }

        //显示头像
        if (holder.headIcon != null) {
          if (userInfo != null && !TextUtils.isEmpty(userInfo.getAvatar())){
                userInfo.getAvatarBitmap(new GetAvatarBitmapCallback() {
                    @Override
                    public void gotResult(int status, String desc, Bitmap bitmap) {
                        if (status == 0) {
                            holder.headIcon.setImageBitmap(bitmap);
                        }else {
                            holder.headIcon.setImageResource(IdHelper.getDrawable(mContext, "jmui_head_icon"));
                            HandleResponseCode.onHandle(mContext, status, false);
                        }
                    }
                });
            }else {
              holder.headIcon.setImageResource(IdHelper.getDrawable(mContext, "jmui_head_icon"));
          }

            //TODO 点击头像事件

        }

        OnLongClickListener longClickListener = new OnLongClickListener() {
            @Override
            public boolean onLongClick(View arg0) {
                // 长按文本弹出菜单
                OnClickListener listener = new OnClickListener() {

                    @Override
                    public void onClick(View v) {
                        if (v.getId() == IdHelper.getViewID(mContext, "jmui_copy_msg_btn")) {
                            if (msg.getContentType() == ContentType.text) {
                                final String content = ((TextContent) msg.getContent()).getText();
                                if (Build.VERSION.SDK_INT > 11) {
                                    ClipboardManager clipboard = (ClipboardManager) mContext
                                            .getSystemService(mContext.CLIPBOARD_SERVICE);
                                    ClipData clip = ClipData.newPlainText("Simple text", content);
                                    clipboard.setPrimaryClip(clip);
                                } else {
                                    ClipboardManager clipboard = (ClipboardManager) mContext
                                            .getSystemService(mContext.CLIPBOARD_SERVICE);
                                    clipboard.setText(content);// 设置Clipboard 的内容
                                    if (clipboard.hasText()) {
                                        clipboard.getText();
                                    }
                                }

                                Toast.makeText(mContext, mContext.getString(IdHelper.getString(mContext,
                                        "jmui_copy_toast")), Toast.LENGTH_SHORT).show();
                                mDialog.dismiss();
                            }
                        } else if (v.getId() == IdHelper.getViewID(mContext, "jmui_delete_msg_btn")) {
                            mConv.deleteMessage(msg.getId());
                            mMsgList.remove(position);
                            notifyDataSetChanged();
                            mDialog.dismiss();
                        } else {
                            mDialog.dismiss();
                        }
                    }
                };
                boolean hide = msg.getContentType() == ContentType.voice;
                String name = userInfo.getUserName();
                mDialog = DialogCreator.createLongPressMessageDialog(mContext, name, hide, listener);
                mDialog.show();
                mDialog.getWindow().setLayout((int) (0.8 * mWidth), WindowManager.LayoutParams.WRAP_CONTENT);
                return true;
            }
        };

        switch (msg.getContentType()) {
            case text:
                handleTextMsg(msg, holder, longClickListener);
                break;
            case image:
                handleImgMsg(msg, holder, position);
                break;
            case voice:
                handleVoiceMsg(msg, holder, position, longClickListener);
                break;
            case eventNotification:
                handleGroupChangeMsg(msg, holder, msgTime);
                break;
            default:
                handleCustomMsg(msg, holder);
        }

        return convertView;
    }


    private void handleCustomMsg(Message msg, ViewHolder holder) {
        CustomContent content = (CustomContent) msg.getContent();
        Boolean isBlackListHint = content.getBooleanValue("blackList");
        if (isBlackListHint != null && isBlackListHint) {
            holder.groupChange.setText(mContext.getString(IdHelper.getString(mContext, "jmui_server_803008")));
        } else {
            holder.groupChange.setVisibility(View.GONE);
        }
    }

    private void handleTextMsg(final Message msg, final ViewHolder holder, OnLongClickListener longClickListener) {
        final String content = ((TextContent) msg.getContent()).getText();
        holder.txtContent.setText(content);
        holder.txtContent.setOnLongClickListener(longClickListener);
        // 检查发送状态，发送方有重发机制
        if (msg.getDirect() == MessageDirect.send) {
            switch (msg.getStatus()) {
                case send_success:
                    holder.sendingIv.clearAnimation();
                    holder.sendingIv.setVisibility(View.GONE);
                    holder.resend.setVisibility(View.GONE);
                    break;
                case send_fail:
                    holder.sendingIv.clearAnimation();
                    holder.sendingIv.setVisibility(View.GONE);
                    holder.resend.setVisibility(View.VISIBLE);
                    break;
                case send_going:
                    sendingTextOrVoice(holder, mSendingAnim, msg);
                    break;
                default:
            }
            // 点击重发按钮，重发消息
            holder.resend.setOnClickListener(new OnClickListener() {

                @Override
                public void onClick(View v) {
                    showResendDialog(holder, mSendingAnim, msg);
                }
            });

        } else {
            if (mIsGroup) {
                holder.displayName.setVisibility(View.VISIBLE);
                if (TextUtils.isEmpty(msg.getFromUser().getNickname())) {
                    holder.displayName.setText(msg.getFromUser().getUserName());
                } else {
                    holder.displayName.setText(msg.getFromUser().getNickname());
                }
            }
        }
    }

    //正在发送文字或语音
    private void sendingTextOrVoice(final ViewHolder holder, Animation sendingAnim, Message msg) {
        holder.sendingIv.setVisibility(View.VISIBLE);
        holder.sendingIv.startAnimation(sendingAnim);
        holder.resend.setVisibility(View.GONE);
        //消息正在发送，重新注册一个监听消息发送完成的Callback
        if (!msg.isSendCompleteCallbackExists()) {
            msg.setOnSendCompleteCallback(new BasicCallback() {
                @Override
                public void gotResult(final int status, final String desc) {
                    holder.sendingIv.setVisibility(View.GONE);
                    holder.sendingIv.clearAnimation();
                    if (status == 803008) {
                        CustomContent customContent = new CustomContent();
                        customContent.setBooleanValue("blackList", true);
                        Message customMsg = mConv.createSendMessage(customContent);
                        addMsgToList(customMsg);
                    } else if (status != 0) {
                        HandleResponseCode.onHandle(mContext, status, false);
                        holder.resend.setVisibility(View.VISIBLE);
                    }
                }
            });
        }
    }

    //重发对话框
    private void showResendDialog(final ViewHolder holder, final Animation sendingAnim, final Message msg) {
        OnClickListener listener = new OnClickListener() {
            @Override
            public void onClick(View view) {
                if (view.getId() == IdHelper.getViewID(mContext, "jmui_cancel_btn")) {
                    mDialog.dismiss();
                } else {
                    mDialog.dismiss();
                    if (msg.getContentType() == ContentType.image) {
                        resendImage(holder, sendingAnim, msg);
                    } else {
                        resendTextOrVoice(holder, sendingAnim, msg);
                    }
                }
            }
        };
        mDialog = DialogCreator.createResendDialog(mContext, listener);
        mDialog.show();
    }

    // 处理图片
    private void handleImgMsg(final Message msg, final ViewHolder holder, final int position) {
        final ImageContent imgContent = (ImageContent) msg.getContent();
        // 先拿本地缩略图
        final String path = imgContent.getLocalThumbnailPath();
        // 接收图片
        if (msg.getDirect() == MessageDirect.receive) {
            if (path == null) {
                //从服务器上拿缩略图
                imgContent.downloadThumbnailImage(msg, new DownloadCompletionCallback() {
                    @Override
                    public void onComplete(int status, String desc, File file) {
                        if (status == 0) {
                            Picasso.with(mContext).load(file).into(holder.picture);
                        }
                    }
                });
            } else {
                setPictureScale(path, holder.picture);
                Picasso.with(mContext).load(new File(path)).into(holder.picture);
            }
            //群聊中显示昵称
            if (mIsGroup) {
                holder.displayName.setVisibility(View.VISIBLE);
                if (TextUtils.isEmpty(msg.getFromUser().getNickname())) {
                    holder.displayName.setText(msg.getFromUser().getUserName());
                } else {
                    holder.displayName.setText(msg.getFromUser().getNickname());
                }
            }

            switch (msg.getStatus()) {
                case receive_fail:
                    holder.picture.setImageResource(IdHelper.getDrawable(mContext, "jmui_fetch_failed"));
                    break;
                default:
            }
            // 发送图片方，直接加载缩略图
        } else {
            try {
                setPictureScale(path, holder.picture);
                Picasso.with(mContext).load(new File(path))
                        .into(holder.picture);
            } catch (NullPointerException e) {
                Picasso.with(mContext).load(IdHelper.getDrawable(mContext, "jmui_picture_not_found"))
                        .into(holder.picture);
            }

            //检查状态
            switch (msg.getStatus()) {
                case send_success:
                    holder.sendingIv.clearAnimation();
                    holder.sendingIv.setVisibility(View.GONE);
                    holder.picture.setAlpha(1.0f);
                    holder.progressTv.setVisibility(View.GONE);
                    holder.resend.setVisibility(View.GONE);
                    break;
                case send_fail:
                    holder.sendingIv.clearAnimation();
                    holder.sendingIv.setVisibility(View.GONE);
                    holder.picture.setAlpha(1.0f);
                    holder.progressTv.setVisibility(View.GONE);
                    holder.resend.setVisibility(View.VISIBLE);
                    break;
                case send_going:
                    sendingImage(holder, mSendingAnim, msg);
                    break;
                default:
                    holder.picture.setAlpha(0.75f);
                    holder.sendingIv.setVisibility(View.VISIBLE);
                    holder.sendingIv.startAnimation(mSendingAnim);
                    holder.progressTv.setVisibility(View.VISIBLE);
                    holder.progressTv.setText("0%");
                    holder.resend.setVisibility(View.GONE);
                    //从别的界面返回聊天界面，继续发送
                    if (!mMsgQueue.isEmpty()) {
                        Message message = mMsgQueue.element();
                        if (message.getId() == msg.getId()) {
                            Log.d(TAG, "Start sending message");
                            JMessageClient.sendMessage(message);
                            mSendMsgId = message.getId();
                            sendingImage(holder, mSendingAnim, message);
                        }
                    }
            }
            // 点击重发按钮，重发图片
            holder.resend.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View arg0) {
                    showResendDialog(holder, mSendingAnim, msg);
                }
            });
        }
        if (holder.picture != null) {

            //TODO 点击图片事件

            holder.picture.setOnLongClickListener(new OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    String name = msg.getFromUser().getUserName();
                    OnClickListener listener = new OnClickListener() {

                        @Override
                        public void onClick(View v) {
                            if (v.getId() == IdHelper.getViewID(mContext, "jmui_delete_msg_btn")) {
                                mConv.deleteMessage(msg.getId());
                                mMsgList.remove(position);
                                notifyDataSetChanged();
                                mDialog.dismiss();
                            } else {
                                mDialog.dismiss();
                            }
                        }
                    };
                    mDialog = DialogCreator.createLongPressMessageDialog(mContext, name, true, listener);
                    mDialog.show();
                    mDialog.getWindow().setLayout((int) (0.8 * mWidth), WindowManager.LayoutParams.WRAP_CONTENT);
                    return true;
                }
            });

        }
    }

    private void sendingImage(final ViewHolder holder, final Animation sendingAnim, final Message msg) {
        holder.picture.setAlpha(0.75f);
        holder.sendingIv.setVisibility(View.VISIBLE);
        holder.sendingIv.startAnimation(sendingAnim);
        holder.progressTv.setVisibility(View.VISIBLE);
        holder.resend.setVisibility(View.GONE);
        //如果图片正在发送，重新注册上传进度Callback
        if (!msg.isContentUploadProgressCallbackExists()) {
            msg.setOnContentUploadProgressCallback(new ProgressUpdateCallback() {
                @Override
                public void onProgressUpdate(double v) {
                    String progressStr = (int) (v * 100) + "%";
                    Log.d(TAG, "msg.getId: " + msg.getId() + " progress: " + progressStr);
                    holder.progressTv.setText(progressStr);
                }
            });
        }
        if (!msg.isSendCompleteCallbackExists()) {
            msg.setOnSendCompleteCallback(new BasicCallback() {
                @Override
                public void gotResult(final int status, String desc) {
                    Log.d(TAG, "Got result status: " + status);
                    if (!mMsgQueue.isEmpty() && mMsgQueue.element().getId() == mSendMsgId) {
                        mMsgQueue.poll();
                        if (!mMsgQueue.isEmpty()) {
                            Message nextMsg = mMsgQueue.element();
                            JMessageClient.sendMessage(nextMsg);
                            mSendMsgId = nextMsg.getId();
                        }
                    }
                    holder.picture.setAlpha(1.0f);
                    holder.sendingIv.clearAnimation();
                    holder.sendingIv.setVisibility(View.GONE);
                    holder.progressTv.setVisibility(View.GONE);
                    if (status == 803008) {
                        CustomContent customContent = new CustomContent();
                        customContent.setBooleanValue("blackList", true);
                        Message customMsg = mConv.createSendMessage(customContent);
                        addMsgToList(customMsg);
                    } else if (status != 0) {
                        HandleResponseCode.onHandle(mContext, status, false);
                        holder.resend.setVisibility(View.VISIBLE);
                    }

                    Message message = mConv.getMessage(msg.getId());
                    mMsgList.set(mMsgList.indexOf(msg), message);
                    Log.d(TAG, "msg.getId " + msg.getId() + " msg.getStatus " + msg.getStatus());
                    Log.d(TAG, "message.getId " + message.getId() + " message.getStatus " + message.getStatus());
                }
            });

        }
    }

    /**
     * 设置图片最小宽高
     *
     * @param path      图片路径
     * @param imageView 显示图片的View
     */
    private void setPictureScale(String path, ImageView imageView) {
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, opts);
        //计算图片缩放比例
        double imageWidth = opts.outWidth;
        double imageHeight = opts.outHeight;
        if (imageWidth < 100 * mDensity) {
            imageHeight = imageHeight * (100 * mDensity / imageWidth);
            imageWidth = 100 * mDensity;
        }
        ViewGroup.LayoutParams params = imageView.getLayoutParams();
        params.width = (int) imageWidth;
        params.height = (int) imageHeight;
        imageView.setLayoutParams(params);
    }

    private void resendTextOrVoice(final ViewHolder holder, Animation sendingAnim, Message msg) {
        holder.resend.setVisibility(View.GONE);
        holder.sendingIv.setVisibility(View.VISIBLE);
        holder.sendingIv.startAnimation(sendingAnim);

        if (!msg.isSendCompleteCallbackExists()) {
            msg.setOnSendCompleteCallback(new BasicCallback() {
                @Override
                public void gotResult(final int status, String desc) {
                    holder.sendingIv.clearAnimation();
                    holder.sendingIv.setVisibility(View.GONE);
                    if (status != 0) {
                        HandleResponseCode.onHandle(mContext, status, false);
                        holder.resend.setVisibility(View.VISIBLE);
                        Log.i(TAG, "Resend message failed!");
                    }
                }
            });
        }

        JMessageClient.sendMessage(msg);
    }

    private void resendImage(final ViewHolder holder, Animation sendingAnim, Message msg) {
        ImageContent imgContent = (ImageContent) msg.getContent();
        final String path = imgContent.getLocalThumbnailPath();
        holder.sendingIv.setVisibility(View.VISIBLE);
        holder.sendingIv.startAnimation(sendingAnim);
        holder.picture.setAlpha(0.75f);
        holder.resend.setVisibility(View.GONE);
        holder.progressTv.setVisibility(View.VISIBLE);
        try {
            // 显示上传进度
            msg.setOnContentUploadProgressCallback(new ProgressUpdateCallback() {
                @Override
                public void onProgressUpdate(final double progress) {
                    mActivity.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            String progressStr = (int) (progress * 100) + "%";
                            holder.progressTv.setText(progressStr);
                        }
                    });
                }
            });
            if (!msg.isSendCompleteCallbackExists()) {
                msg.setOnSendCompleteCallback(new BasicCallback() {
                    @Override
                    public void gotResult(final int status, String desc) {
                        holder.sendingIv.clearAnimation();
                        holder.sendingIv.setVisibility(View.GONE);
                        holder.progressTv.setVisibility(View.GONE);
                        holder.picture.setAlpha(1.0f);
                        if (status != 0) {
                            HandleResponseCode.onHandle(mContext, status, false);
                            holder.resend.setVisibility(View.VISIBLE);
                        }
                    }
                });
            }
            JMessageClient.sendMessage(msg);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void handleVoiceMsg(final Message msg, final ViewHolder holder, final int position,
    OnLongClickListener longClickListener) {
        final VoiceContent content = (VoiceContent) msg.getContent();
        final MessageDirect msgDirect = msg.getDirect();
        int length = content.getDuration();
        String voiceLength = length + mContext.getString(IdHelper.getString(mContext, "jmui_symbol_second"));
        holder.voiceLength.setText(voiceLength);
        //控制语音长度显示，长度增幅随语音长度逐渐缩小
        int width = (int) (-0.04 * length * length + 4.526 * length + 75.214);
        holder.txtContent.setWidth((int) (width * mDensity));
        holder.txtContent.setOnLongClickListener(longClickListener);
        if (msgDirect == MessageDirect.send) {
            holder.voice.setImageResource(IdHelper.getDrawable(mContext, "jmui_send_3"));
            switch (msg.getStatus()) {
                case send_success:
                    holder.sendingIv.clearAnimation();
                    holder.sendingIv.setVisibility(View.GONE);
                    holder.resend.setVisibility(View.GONE);
                    break;
                case send_fail:
                    holder.sendingIv.clearAnimation();
                    holder.sendingIv.setVisibility(View.GONE);
                    holder.resend.setVisibility(View.VISIBLE);
                    break;
                case send_going:
                    sendingTextOrVoice(holder, mSendingAnim, msg);
                    break;
                default:
            }

            holder.resend.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View arg0) {
                    if (msg.getContent() != null) {
                        showResendDialog(holder, mSendingAnim, msg);
                    }
                    else {
                        Toast.makeText(mContext, mContext.getString(IdHelper.getString(mContext,
                                        "jmui_sdcard_not_exist_toast")), Toast.LENGTH_SHORT).show();
                    }
                }
            });
        } else switch (msg.getStatus()) {
            case receive_success:
                if (mIsGroup) {
                    holder.displayName.setVisibility(View.VISIBLE);
                    if (TextUtils.isEmpty(msg.getFromUser().getNickname())) {
                        holder.displayName.setText(msg.getFromUser().getUserName());
                    } else {
                        holder.displayName.setText(msg.getFromUser().getNickname());
                    }
                }
                holder.voice.setImageResource(IdHelper.getDrawable(mContext, "jmui_receive_3"));
                // 收到语音，设置未读
                if (msg.getContent().getBooleanExtra("isReaded") == null
                        || !msg.getContent().getBooleanExtra("isReaded")) {
                    mConv.updateMessageExtra(msg, "isReaded", false);
                    holder.readStatus.setVisibility(View.VISIBLE);
                    if (mIndexList.size() > 0) {
                        if (!mIndexList.contains(position)) {
                            addTolistAndSort(position);
                        }
                    } else {
                        addTolistAndSort(position);
                    }
                    if (nextPlayPosition == position && autoPlay) {
                        playVoice(position, holder, false);
                    }
                } else if (msg.getContent().getBooleanExtra("isReaded").equals(true)) {
                    holder.readStatus.setVisibility(View.GONE);
                }
                break;
            case receive_fail:
                holder.voice.setImageResource(IdHelper.getDrawable(mContext, "jmui_receive_3"));
                // 接收失败，从服务器上下载
                content.downloadVoiceFile(msg,
                        new DownloadCompletionCallback() {
                            @Override
                            public void onComplete(int status, String desc, File file) {
                                if (status != 0) {
                                    Toast.makeText(mContext, mContext.getString(IdHelper.getString(mContext,
                                                    "jmui_voice_fetch_failed_toast")), Toast.LENGTH_SHORT).show();
                                } else {
                                    Log.i("VoiceMessage", "reload success");
                                }
                            }
                        });
                break;
            case receive_going:
                break;
            default:
        }


        holder.txtContent.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View arg0) {
                if (!FileHelper.isSdCardExist() && msg.getDirect() == MessageDirect.send) {
                    Toast.makeText(mContext, mContext.getString(IdHelper.getString(mContext, 
                            "jmui_sdcard_not_exist_toast")), Toast.LENGTH_SHORT).show();
                    return;
                }
                // 如果之前存在播放动画，无论这次点击触发的是暂停还是播放，停止上次播放的动画
                if (mVoiceAnimation != null) {
                    mVoiceAnimation.stop();
                }
                // 播放中点击了正在播放的Item 则暂停播放
                if (mp.isPlaying() && mPosition == position) {
                    if (msgDirect == MessageDirect.send) {
                        holder.voice.setImageResource(IdHelper.getAnim(mContext, "jmui_voice_send"));
                    } else {
                        holder.voice.setImageResource(IdHelper.getAnim(mContext, "jmui_voice_receive"));
                    }
                    mVoiceAnimation = (AnimationDrawable) holder.voice.getDrawable();
                    pauseVoice();
                    mVoiceAnimation.stop();
                    // 开始播放录音
                } else if (msgDirect == MessageDirect.send) {
                    holder.voice.setImageResource(IdHelper.getAnim(mContext, "jmui_voice_send"));
                    mVoiceAnimation = (AnimationDrawable) holder.voice.getDrawable();

                    // 继续播放之前暂停的录音
                    if (mSetData && mPosition == position) {
                        mVoiceAnimation.start();
                        mp.start();
                        // 否则重新播放该录音或者其他录音
                    } else {
                        playVoice(position, holder, true);
                    }
                    // 语音接收方特殊处理，自动连续播放未读语音
                } else {
                    try {
                        // 继续播放之前暂停的录音
                        if (mSetData && mPosition == position) {
                            if (mVoiceAnimation != null) {
                                mVoiceAnimation.start();
                            }
                            mp.start();
                            // 否则开始播放另一条录音
                        } else {
                            // 选中的录音是否已经播放过，如果未播放，自动连续播放这条语音之后未播放的语音
                            if (msg.getContent().getBooleanExtra("isReaded") == null
                                    || !msg.getContent().getBooleanExtra("isReaded")) {
                                autoPlay = true;
                                playVoice(position, holder, false);
                                // 否则直接播放选中的语音
                            } else {
                                holder.voice.setImageResource(IdHelper.getAnim(mContext, "jmui_voice_receive"));
                                mVoiceAnimation = (AnimationDrawable) holder.voice.getDrawable();
                                playVoice(position, holder, false);
                            }
                        }
                    } catch (IllegalArgumentException e) {
                        e.printStackTrace();
                    } catch (SecurityException e) {
                        e.printStackTrace();
                    } catch (IllegalStateException e) {
                        e.printStackTrace();
                    }
                }
            }
        });
    }

    private void handleGroupChangeMsg(Message msg, ViewHolder holder, TextView msgTime) {
        UserInfo myInfo = JMessageClient.getMyInfo();
        GroupInfo groupInfo = (GroupInfo) msg.getTargetInfo();
        String content = ((EventNotificationContent) msg.getContent()).getEventText();
        EventNotificationContent.EventNotificationType type = ((EventNotificationContent) msg
                .getContent()).getEventNotificationType();
        switch (type) {
            case group_member_added:
                holder.groupChange.setText(content);
                holder.groupChange.setVisibility(View.VISIBLE);
                break;
            case group_member_exit:
                holder.groupChange.setVisibility(View.GONE);
                msgTime.setVisibility(View.GONE);
                break;
            case group_member_removed:
                List<String> userNames = ((EventNotificationContent) msg.getContent()).getUserNames();
                //被删除的人显示EventNotification
                if (userNames.contains(myInfo.getNickname()) || userNames.contains(myInfo.getUserName())) {
                    holder.groupChange.setText(content);
                    holder.groupChange.setVisibility(View.VISIBLE);
                    //群主亦显示
                } else if (myInfo.getUserName().equals(groupInfo.getGroupOwner())) {
                    holder.groupChange.setText(content);
                    holder.groupChange.setVisibility(View.VISIBLE);
                } else {
                    holder.groupChange.setVisibility(View.GONE);
                    msgTime.setVisibility(View.GONE);
                }
                break;
        }
    }

    private void playVoice(final int position, final ViewHolder holder, final boolean isSender) {
        // 记录播放录音的位置
        mPosition = position;
        Message msg = mMsgList.get(position);
        if (autoPlay) {
            mConv.updateMessageExtra(msg, "isReaded", true);
            holder.readStatus.setVisibility(View.GONE);
            if (mVoiceAnimation != null) {
                mVoiceAnimation.stop();
                mVoiceAnimation = null;
            }
            holder.voice.setImageResource(mContext.getResources().getIdentifier("jmui_voice_receive",
                    "anim", mContext.getPackageName()));
            mVoiceAnimation = (AnimationDrawable) holder.voice.getDrawable();
        }
        try {
            mp.reset();
            VoiceContent vc = (VoiceContent) msg.getContent();
            Log.i(TAG, "content.getLocalPath:" + vc.getLocalPath());
            mFIS = new FileInputStream(vc.getLocalPath());
            mFD = mFIS.getFD();
            mp.setDataSource(mFD);
            if (mIsEarPhoneOn) {
                mp.setAudioStreamType(AudioManager.STREAM_VOICE_CALL);
            } else {
                mp.setAudioStreamType(AudioManager.STREAM_MUSIC);
            }
            mp.prepare();
            mp.setOnPreparedListener(new OnPreparedListener() {
                @Override
                public void onPrepared(MediaPlayer mp) {
                    mVoiceAnimation.start();
                    mp.start();
                }
            });
            mp.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                @Override
                public void onCompletion(MediaPlayer mp) {
                    mVoiceAnimation.stop();
                    mp.reset();
                    mSetData = false;
                    if (isSender) {
                        holder.voice.setImageResource(IdHelper.getDrawable(mContext, "jmui_send_3"));
                    } else {
                        holder.voice.setImageResource(IdHelper.getDrawable(mContext, "jmui_receive_3"));
                    }
                    if (autoPlay) {
                        int curCount = mIndexList.indexOf(position);
                        Log.d(TAG, "curCount = " + curCount);
                        if (curCount + 1 >= mIndexList.size()) {
                            nextPlayPosition = -1;
                            autoPlay = false;
                        } else {
                            nextPlayPosition = mIndexList.get(curCount + 1);
                            notifyDataSetChanged();
                        }
                        mIndexList.remove(curCount);
                    }
                }
            });
        } catch (FileNotFoundException e) {
            Toast.makeText(mContext, mContext.getString(IdHelper.getString(mContext, "jmui_file_not_found_toast")),
                    Toast.LENGTH_SHORT).show();
        } catch (IOException e) {
            Toast.makeText(mActivity, mContext.getString(IdHelper.getString(mContext, "jmui_file_not_found_toast")),
                    Toast.LENGTH_SHORT).show();
        } finally {
            try {
                if (mFIS != null) {
                    mFIS.close();
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

    }

    private void pauseVoice() {
        mp.pause();
        mSetData = true;
    }

    private void addTolistAndSort(int position) {
        mIndexList.add(position);
        Collections.sort(mIndexList);
    }

    public void stopMediaPlayer() {
        if (mp.isPlaying())
            mp.stop();
    }

    public static class ViewHolder {
        CircleImageView headIcon;
        TextView displayName;
        TextView txtContent;
        ImageView picture;
        TextView progressTv;
        ImageButton resend;
        TextView voiceLength;
        ImageView voice;
        // 录音是否播放过的标志
        ImageView readStatus;
        TextView groupChange;
        ImageView sendingIv;
    }
}
