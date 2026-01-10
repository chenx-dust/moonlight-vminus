package com.limelight;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.UnknownHostException;

import com.limelight.binding.PlatformBinding;
import com.limelight.binding.crypto.AndroidCryptoProvider;
import com.limelight.computers.ComputerManagerService;
import com.limelight.grid.PcGridAdapter;
import com.limelight.grid.assets.DiskAssetLoader;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.NvApp;
import com.limelight.nvstream.http.NvHTTP;
import com.limelight.nvstream.http.PairingManager;
import com.limelight.nvstream.http.PairingManager.PairResult;
import com.limelight.nvstream.http.PairingManager.PairState;
import com.limelight.nvstream.wol.WakeOnLanSender;
import com.limelight.preferences.AddComputerManually;
import com.limelight.preferences.GlPreferences;
import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.preferences.StreamSettings;
import com.limelight.ui.AdapterFragment;
import com.limelight.ui.AdapterFragmentCallbacks;
import com.limelight.utils.Dialog;
import com.limelight.utils.HelpLauncher;
import com.limelight.utils.Iperf3Tester;
import com.limelight.utils.ServerHelper;
import com.limelight.utils.ShortcutHelper;
import com.limelight.utils.UiHelper;
import com.limelight.utils.AppCacheManager;
import com.limelight.utils.CacheHelper;
import com.limelight.dialogs.AddressSelectionDialog;

import org.xmlpull.v1.XmlPullParserException;

import java.io.StringReader;
import java.util.List;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.net.Uri;
import android.opengl.GLSurfaceView;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.provider.Settings;
import android.util.LruCache;
import android.view.ContextMenu;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ContextMenu.ContextMenuInfo;
import android.widget.AbsListView;
import android.widget.GridView;
import android.widget.ImageButton;
import android.widget.RelativeLayout;
import android.widget.Toast;
import android.widget.TextView;
import android.widget.AdapterView.AdapterContextMenuInfo;
import android.view.LayoutInflater;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.view.animation.AnimationUtils;
import android.view.animation.LayoutAnimationController;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONException;
import org.json.JSONObject;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

import android.app.AlertDialog;
import android.content.SharedPreferences;

public class PcView extends Activity implements AdapterFragmentCallbacks {
    private RelativeLayout noPcFoundLayout;
    private PcGridAdapter pcGridAdapter;
    private AbsListView pcListView;
    private boolean isFirstLoad = true;
    private ShortcutHelper shortcutHelper;

    // 防抖机制：合并短时间内的多次刷新请求
    private final Handler refreshHandler = new Handler(Looper.getMainLooper());
    private Runnable pendingRefreshRunnable;
    private static final long REFRESH_DEBOUNCE_DELAY = 150; // 150ms 防抖延迟
    private int selectedPosition = -1;
    private ComputerManagerService.ComputerManagerBinder managerBinder;
    private boolean freezeUpdates, runningPolling, inForeground, completeOnCreateCalled;

    private AddressSelectionDialog currentAddressDialog;

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        public void onServiceConnected(ComponentName className, IBinder binder) {
            final ComputerManagerService.ComputerManagerBinder localBinder =
                    ((ComputerManagerService.ComputerManagerBinder)binder);

            // Wait in a separate thread to avoid stalling the UI
            new Thread() {
                @Override
                public void run() {
                    // Wait for the binder to be ready
                    localBinder.waitForReady();

                    // Now make the binder visible
                    managerBinder = localBinder;

                    // Start updates
                    startComputerUpdates();

                    // Force a keypair to be generated early to avoid discovery delays
                    new AndroidCryptoProvider(PcView.this).getClientCertificate();
                }
            }.start();
        }

        public void onServiceDisconnected(ComponentName className) {
            managerBinder = null;
        }
    };

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);

        // Only reinitialize views if completeOnCreate() was called
        // before this callback. If it was not, completeOnCreate() will
        // handle initializing views with the config change accounted for.
        // This is not prone to races because both callbacks are invoked
        // in the main thread.
        if (completeOnCreateCalled) {
            // Reinitialize views just in case orientation changed
            initializeViews();
        }
    }

    private final static int PAIR_ID = 2;
    private final static int UNPAIR_ID = 3;
    private final static int WOL_ID = 4;
    private final static int DELETE_ID = 5;
    private final static int RESUME_ID = 6;
    private final static int QUIT_ID = 7;
    private final static int VIEW_DETAILS_ID = 8;
    private final static int FULL_APP_LIST_ID = 9;
    private final static int TEST_NETWORK_ID = 10;
    private final static int GAMESTREAM_EOL_ID = 11;
    private final static int SLEEP_ID = 12;
    private final static int IPERF3_TEST_ID = 13;

    public String clientName;
    private LruCache<String, Bitmap> bitmapLruCache;

    // 添加场景配置相关常量
    private static final String SCENE_PREF_NAME = "SceneConfigs";
    private static final String SCENE_KEY_PREFIX = "scene_";

    private void initializeViews() {
        setContentView(R.layout.activity_pc_view);

        UiHelper.notifyNewRootView(this);

        // Allow floating expanded PiP overlays while browsing PCs
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            setShouldDockBigOverlays(false);
        }

        clientName = Settings.Global.getString(this.getContentResolver(), "device_name");

        if (getWindow().getDecorView().getRootView() != null) {
            initSceneButtons();
        }

        // Set the correct layout for the PC grid
        pcGridAdapter.updateLayoutWithPreferences(this, PreferenceConfiguration.readPreferences(this));

        // Setup the list view
        ImageButton settingsButton = findViewById(R.id.settingsButton);
        ImageButton restoreSessionButton = findViewById(R.id.restoreSessionButton);
        ImageButton aboutButton = findViewById(R.id.aboutButton);

        settingsButton.setOnClickListener(v -> startActivity(new Intent(PcView.this, StreamSettings.class)));
        restoreSessionButton.setOnClickListener(v -> restoreLastSession());
        if (aboutButton != null) {
            aboutButton.setOnClickListener(v -> showAboutDialog());
        }

        getFragmentManager().beginTransaction()
            .replace(R.id.pcFragmentContainer, new AdapterFragment())
            .commitAllowingStateLoss();

        noPcFoundLayout = findViewById(R.id.no_pc_found_layout);

        // 确保添加卡片存在
        addAddComputerCard();

        if (pcGridAdapter.getCount() == 0 || pcGridAdapter.getCount() == 1 &&
            PcGridAdapter.isAddComputerCard((ComputerObject) pcGridAdapter.getItem(0))) {
            noPcFoundLayout.setVisibility(View.VISIBLE);
        }
        else {
            noPcFoundLayout.setVisibility(View.INVISIBLE);
        }

        // 刷新数据（首次加载时不使用防抖）
        if (isFirstLoad) {
            // 取消任何待处理的防抖刷新
            if (pendingRefreshRunnable != null) {
                refreshHandler.removeCallbacks(pendingRefreshRunnable);
                pendingRefreshRunnable = null;
            }
            // 首次加载时不直接刷新，等 receiveAdapterView 设置好 adapter 后再统一触发动画
            // 如果 pcListView 已经存在（配置变化重建），则直接刷新
            if (pcListView != null) {
                pcGridAdapter.notifyDataSetChanged();
            }
        } else {
            // 非首次加载，使用防抖刷新
            debouncedNotifyDataSetChanged();
        }
    }

    /**
     * 更新眼睛图标按钮图标
     */
    private void updateToggleUnpairedButtonIcon(ImageButton button) {
        if (button == null || pcGridAdapter == null) return;

        if (pcGridAdapter.isShowUnpairedDevices()) {
            button.setImageResource(R.drawable.ic_visibility);
        } else {
            button.setImageResource(R.drawable.ic_visibility_off);
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Assume we're in the foreground when created to avoid a race
        // between binding to CMS and onResume()
        inForeground = true;

        // Create cache for images
        int maxMemory = (int) (Runtime.getRuntime().maxMemory() / 1024);
        int cacheSize = maxMemory / 8;
        bitmapLruCache = new LruCache<>(cacheSize) {
            @Override
            protected int sizeOf(String key, Bitmap value) {
                // 计算每个Bitmap占用的内存大小（以KB为单位）
                return value.getByteCount() / 1024;
            }
        };

        // Create a GLSurfaceView to fetch GLRenderer unless we have
        // a cached result already.
        final GlPreferences glPrefs = GlPreferences.readPreferences(this);
        if (!glPrefs.savedFingerprint.equals(Build.FINGERPRINT) || glPrefs.glRenderer.isEmpty()) {
            GLSurfaceView surfaceView = new GLSurfaceView(this);
            surfaceView.setRenderer(new GLSurfaceView.Renderer() {
                @Override
                public void onSurfaceCreated(GL10 gl10, EGLConfig eglConfig) {
                    // Save the GLRenderer string so we don't need to do this next time
                    glPrefs.glRenderer = gl10.glGetString(GL10.GL_RENDERER);
                    glPrefs.savedFingerprint = Build.FINGERPRINT;
                    glPrefs.writePreferences();

                    LimeLog.info("Fetched GL Renderer: " + glPrefs.glRenderer);

                    runOnUiThread(() -> completeOnCreate());
                }

                @Override
                public void onSurfaceChanged(GL10 gl10, int i, int i1) {
                }

                @Override
                public void onDrawFrame(GL10 gl10) {
                }
            });
            setContentView(surfaceView);
        }
        else {
            LimeLog.info("Cached GL Renderer: " + glPrefs.glRenderer);
            completeOnCreate();
        }
    }

    private void initSceneButtons() {
        try {
            int[] sceneButtonIds = {
                R.id.scene1Btn, R.id.scene2Btn, 
                R.id.scene3Btn, R.id.scene4Btn, R.id.scene5Btn
            };

            for (int i = 0; i < sceneButtonIds.length; i++) {
                final int sceneNumber = i + 1;
                ImageButton btn = findViewById(sceneButtonIds[i]);
                
                if (btn == null) {
                    LimeLog.warning("Scene button "+ sceneNumber +" (ID: "+getResources().getResourceName(sceneButtonIds[i])+") not found!");
                    continue;
                }

                btn.setOnClickListener(v -> applySceneConfiguration(sceneNumber));
                btn.setOnLongClickListener(v -> {
                    showSaveConfirmationDialog(sceneNumber);
                    return true;
                });
            }
        } catch (Exception e) {
            LimeLog.warning("Scene init failed: "+ e);
            e.printStackTrace();
        }
    }

    @SuppressLint({"DefaultLocale", "StringFormatMatches"})
    private void applySceneConfiguration(int sceneNumber) {
        try {
            SharedPreferences prefs = getSharedPreferences(SCENE_PREF_NAME, MODE_PRIVATE);
            String configJson = prefs.getString(SCENE_KEY_PREFIX + sceneNumber, null);
            
            if (configJson != null) {
                JSONObject config = new JSONObject(configJson);
                // 解析配置参数
                int width = config.optInt("width", 1920);
                int height = config.optInt("height", 1080);
                int fps = config.optInt("fps", 60);
                int bitrate = config.optInt("bitrate", 10000);
                String videoFormat = config.optString("videoFormat", "auto");
                boolean enableHdr = config.optBoolean("enableHdr", false);
                boolean enablePerfOverlay = config.optBoolean("enablePerfOverlay", false);
                
                // 使用副本配置进行操作
                PreferenceConfiguration configPrefs = PreferenceConfiguration.readPreferences(this).copy();
                configPrefs.width = width;
                configPrefs.height = height;
                configPrefs.fps = fps;
                configPrefs.bitrate = bitrate;
                configPrefs.videoFormat = PreferenceConfiguration.FormatOption.valueOf(videoFormat);
                configPrefs.enableHdr = enableHdr;
                configPrefs.enablePerfOverlay = enablePerfOverlay;
                
                // 保存并检查结果
                if (!configPrefs.writePreferences(this)) {
                    Toast.makeText(this, getResources().getString(R.string.config_save_failed), Toast.LENGTH_SHORT).show();
                    return;
                }
                
                pcGridAdapter.updateLayoutWithPreferences(this, configPrefs);
                
                Toast.makeText(this, getResources().getString(R.string.scene_config_applied,
                    sceneNumber, width, height, fps, bitrate / 1000.0, videoFormat, enableHdr ? "On" : "Off"), Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, getResources().getString(R.string.scene_not_configured, sceneNumber), Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            LimeLog.warning("Scene apply failed: "+ e);
            runOnUiThread(() -> Toast.makeText(PcView.this, getResources().getString(R.string.config_apply_failed), Toast.LENGTH_SHORT).show());
        }
    }

    private void showSaveConfirmationDialog(int sceneNumber) {
        new AlertDialog.Builder(this, R.style.AppDialogStyle)
            .setTitle(getResources().getString(R.string.save_to_scene, sceneNumber))
            .setMessage(getResources().getString(R.string.overwrite_current_config))
            .setPositiveButton(getResources().getString(R.string.dialog_button_save), (dialog, which) -> saveCurrentConfiguration(sceneNumber))
            .setNegativeButton(getResources().getString(R.string.dialog_button_cancel), null)
            .show();
    }

    private void saveCurrentConfiguration(int sceneNumber) {
        try {
            PreferenceConfiguration configPrefs = PreferenceConfiguration.readPreferences(this);
            JSONObject config = new JSONObject();
            config.put("width", configPrefs.width);
            config.put("height", configPrefs.height);
            config.put("fps", configPrefs.fps);
            config.put("bitrate", configPrefs.bitrate);
            config.put("videoFormat", configPrefs.videoFormat.toString());
            config.put("enableHdr", configPrefs.enableHdr);
            config.put("enablePerfOverlay", configPrefs.enablePerfOverlay);
            
            // 保存到SharedPreferences
            getSharedPreferences(SCENE_PREF_NAME, MODE_PRIVATE)
                .edit()
                .putString(SCENE_KEY_PREFIX + sceneNumber, config.toString())
                .apply();
            
            Toast.makeText(this, getResources().getString(R.string.scene_saved_successfully, sceneNumber), Toast.LENGTH_SHORT).show();
        } catch (JSONException e) {
            Toast.makeText(this, getResources().getString(R.string.config_save_failed), Toast.LENGTH_SHORT).show();
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private void completeOnCreate() {
        completeOnCreateCalled = true;

        shortcutHelper = new ShortcutHelper(this);

        UiHelper.setLocale(this);

        // Bind to the computer manager service
        bindService(new Intent(PcView.this, ComputerManagerService.class), serviceConnection,
                Service.BIND_AUTO_CREATE);

        pcGridAdapter = new PcGridAdapter(this, PreferenceConfiguration.readPreferences(this));

        initializeViews();
    }

    private void startComputerUpdates() {
        // Only allow polling to start if we're bound to CMS, polling is not already running,
        // and our activity is in the foreground.
        if (managerBinder != null && !runningPolling && inForeground) {
            freezeUpdates = false;
            managerBinder.startPolling(details -> {
                if (!freezeUpdates) {
                    PcView.this.runOnUiThread(() -> updateComputer(details));

                    // Add a launcher shortcut for this PC (off the main thread to prevent ANRs)
                    if (details.pairState == PairState.PAIRED) {
                        shortcutHelper.createAppViewShortcutForOnlineHost(details);
                    }
                }
            });
            runningPolling = true;
        }
    }

    private void stopComputerUpdates(boolean wait) {
        if (managerBinder != null) {
            if (!runningPolling) {
                return;
            }

            freezeUpdates = true;

            managerBinder.stopPolling();

            if (wait) {
                managerBinder.waitForPollingStopped();
            }

            runningPolling = false;
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        if (managerBinder != null) {
            unbindService(serviceConnection);
        }
        
        // 关闭地址选择对话框
        if (currentAddressDialog != null) {
            currentAddressDialog.dismiss();
            currentAddressDialog = null;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();

        // Display a decoder crash notification if we've returned after a crash
        UiHelper.showDecoderCrashDialog(this);

        inForeground = true;
        startComputerUpdates();
    }

    @Override
    protected void onPause() {
        super.onPause();

        inForeground = false;
        stopComputerUpdates(false);
    }

    @Override
    protected void onStop() {
        super.onStop();

        Dialog.closeDialogs();
    }

    @Override
    public void onCreateContextMenu(ContextMenu menu, View v, ContextMenuInfo menuInfo) {
        stopComputerUpdates(false);

        // Call superclass
        super.onCreateContextMenu(menu, v, menuInfo);

        int position = -1;
        if (menuInfo instanceof AdapterContextMenuInfo) {
            position = ((AdapterContextMenuInfo) menuInfo).position;
        } else if (v != null && v.getTag() instanceof Integer) {
            position = (Integer) v.getTag();
        } else if (selectedPosition >= 0) {
            position = selectedPosition;
        }

        if (position < 0) return;

        ComputerObject computer = (ComputerObject) pcGridAdapter.getItem(position);

        // 添加卡片不显示上下文菜单
        if (PcGridAdapter.isAddComputerCard(computer)) {
            return;
        }

        // Add a header with PC status details
        menu.clearHeader();
        String headerTitle = computer.details.name + " - ";
        switch (computer.details.state)
        {
            case ONLINE:
                headerTitle += getResources().getString(R.string.pcview_menu_header_online);
                break;
            case OFFLINE:
                menu.setHeaderIcon(R.drawable.ic_pc_offline);
                headerTitle += getResources().getString(R.string.pcview_menu_header_offline);
                break;
            case UNKNOWN:
                headerTitle += getResources().getString(R.string.pcview_menu_header_unknown);
                break;
        }

        menu.setHeaderTitle(headerTitle);

        // Inflate the context menu
        if (computer.details.state == ComputerDetails.State.OFFLINE ||
            computer.details.state == ComputerDetails.State.UNKNOWN) {
            menu.add(Menu.NONE, WOL_ID, 1, getResources().getString(R.string.pcview_menu_send_wol));
        }
        else if (computer.details.pairState != PairState.PAIRED) {
            menu.add(Menu.NONE, PAIR_ID, 1, getResources().getString(R.string.pcview_menu_pair_pc));
            if (computer.details.nvidiaServer) {
                menu.add(Menu.NONE, GAMESTREAM_EOL_ID, 2, getResources().getString(R.string.pcview_menu_eol));
            }
        }
        else {
            if (computer.details.runningGameId != 0) {
                menu.add(Menu.NONE, RESUME_ID, 1, getResources().getString(R.string.applist_menu_resume));
                menu.add(Menu.NONE, QUIT_ID, 2, getResources().getString(R.string.applist_menu_quit));
            }

            if (computer.details.nvidiaServer) {
                menu.add(Menu.NONE, GAMESTREAM_EOL_ID, 3, getResources().getString(R.string.pcview_menu_eol));
            }

            menu.add(Menu.NONE, FULL_APP_LIST_ID, 4, getResources().getString(R.string.pcview_menu_app_list));
            menu.add(Menu.NONE, SLEEP_ID, 8, getResources().getString(R.string.send_sleep_command));
        }

        menu.add(Menu.NONE, TEST_NETWORK_ID, 5, getResources().getString(R.string.pcview_menu_test_network));
        menu.add(Menu.NONE, IPERF3_TEST_ID, 6, getResources().getString(R.string.network_bandwidth_test));
        menu.add(Menu.NONE, DELETE_ID, 6, getResources().getString(R.string.pcview_menu_delete_pc));
        menu.add(Menu.NONE, VIEW_DETAILS_ID, 7,  getResources().getString(R.string.pcview_menu_details));
    }

    @Override
    public void onContextMenuClosed(Menu menu) {
        // For some reason, this gets called again _after_ onPause() is called on this activity.
        // startComputerUpdates() manages this and won't actual start polling until the activity
        // returns to the foreground.
        startComputerUpdates();
    }

    private void doPair(final ComputerDetails computer) {
        if (computer.state == ComputerDetails.State.OFFLINE || computer.activeAddress == null) {
            Toast.makeText(PcView.this, getResources().getString(R.string.pair_pc_offline), Toast.LENGTH_SHORT).show();
            return;
        }
        if (managerBinder == null) {
            Toast.makeText(PcView.this, getResources().getString(R.string.error_manager_not_running), Toast.LENGTH_LONG).show();
            return;
        }

        Toast.makeText(PcView.this, getResources().getString(R.string.pairing), Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            String message = null;
            boolean success = false;
            
            try {
                // Stop updates and wait while pairing
                stopComputerUpdates(true);

                NvHTTP httpConn = new NvHTTP(
                    ServerHelper.getCurrentAddressFromComputer(computer),
                    computer.httpsPort, 
                    managerBinder.getUniqueId(), 
                    clientName, 
                    computer.serverCert,
                    PlatformBinding.getCryptoProvider(PcView.this)
                );
                
                if (httpConn.getPairState() == PairState.PAIRED) {
                    // Already paired, open the app list directly
                    success = true;
                } else {
                    // Generate PIN and show pairing dialog
                    final String pinStr = PairingManager.generatePinString();
                    Dialog.displayDialog(
                        PcView.this, 
                        getResources().getString(R.string.pair_pairing_title),
                        getResources().getString(R.string.pair_pairing_msg) + " " + pinStr + "\n\n" +
                            getResources().getString(R.string.pair_pairing_help), 
                        false
                    );

                    PairingManager pm = httpConn.getPairingManager();
                    PairResult pairResult = pm.pair(httpConn.getServerInfo(true), pinStr);
                    PairState pairState = pairResult.state;

                    switch (pairState) {
                        case PIN_WRONG:
                            message = getResources().getString(R.string.pair_incorrect_pin);
                            break;
                        case FAILED:
                            message = computer.runningGameId != 0 
                                ? getResources().getString(R.string.pair_pc_ingame)
                                : getResources().getString(R.string.pair_fail);
                            break;
                        case ALREADY_IN_PROGRESS:
                            message = getResources().getString(R.string.pair_already_in_progress);
                            break;
                        case PAIRED:
                            success = true;
                            // Pin this certificate for later HTTPS use
                            managerBinder.getComputer(computer.uuid).serverCert = pm.getPairedCert();
                            
                            // Save pair name using SharedPreferences
                            SharedPreferences sharedPreferences = getSharedPreferences("pair_name_map", MODE_PRIVATE);
                            sharedPreferences.edit().putString(computer.uuid, pairResult.pairName).apply();
                            
                            // Invalidate reachability information after pairing
                            managerBinder.invalidateStateForComputer(computer.uuid);
                            break;
                    }
                }
            } catch (UnknownHostException e) {
                message = getResources().getString(R.string.error_unknown_host);
            } catch (FileNotFoundException e) {
                message = getResources().getString(R.string.error_404);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt(); // Restore interrupt status
                message = getResources().getString(R.string.pair_fail);
            } catch (XmlPullParserException | IOException e) {
                e.printStackTrace();
                message = e.getMessage();
            } finally {
                Dialog.closeDialogs();
            }

            final String toastMessage = message;
            final boolean toastSuccess = success;
            runOnUiThread(() -> {
                if (toastMessage != null) {
                    Toast.makeText(PcView.this, toastMessage, Toast.LENGTH_LONG).show();
                }

                if (toastSuccess) {
                    // Open the app list after a successful pairing attempt
                    doAppList(computer, true, false);
                } else {
                    // Start polling again if we're still in the foreground
                    startComputerUpdates();
                }
            });
        }).start();
    }

    private void doWakeOnLan(final ComputerDetails computer) {
        if (computer.state == ComputerDetails.State.ONLINE) {
            Toast.makeText(PcView.this, getResources().getString(R.string.wol_pc_online), Toast.LENGTH_SHORT).show();
            return;
        }

        if (computer.macAddress == null) {
            Toast.makeText(PcView.this, getResources().getString(R.string.wol_no_mac), Toast.LENGTH_SHORT).show();
            return;
        }

        new Thread(() -> {
            String message;
            try {
                WakeOnLanSender.sendWolPacket(computer);
                message = getResources().getString(R.string.wol_waking_msg);
            } catch (IOException e) {
                message = getResources().getString(R.string.wol_fail);
            }

            final String toastMessage = message;
            runOnUiThread(() -> Toast.makeText(PcView.this, toastMessage, Toast.LENGTH_LONG).show());
        }).start();
    }

    private void doUnpair(final ComputerDetails computer) {
        if (computer.state == ComputerDetails.State.OFFLINE || computer.activeAddress == null) {
            Toast.makeText(PcView.this, getResources().getString(R.string.error_pc_offline), Toast.LENGTH_SHORT).show();
            return;
        }
        if (managerBinder == null) {
            Toast.makeText(PcView.this, getResources().getString(R.string.error_manager_not_running), Toast.LENGTH_LONG).show();
            return;
        }

        Toast.makeText(PcView.this, getResources().getString(R.string.unpairing), Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            String message;
            try {
                NvHTTP httpConn = new NvHTTP(ServerHelper.getCurrentAddressFromComputer(computer),
                        computer.httpsPort, managerBinder.getUniqueId(), clientName, computer.serverCert,
                        PlatformBinding.getCryptoProvider(PcView.this));
                
                PairState pairState = httpConn.getPairState();
                if (pairState == PairState.PAIRED) {
                    httpConn.unpair();
                    message = httpConn.getPairState() == PairState.NOT_PAIRED 
                            ? getResources().getString(R.string.unpair_success)
                            : getResources().getString(R.string.unpair_fail);
                } else {
                    message = getResources().getString(R.string.unpair_error);
                }
            } catch (UnknownHostException e) {
                message = getResources().getString(R.string.error_unknown_host);
            } catch (FileNotFoundException e) {
                message = getResources().getString(R.string.error_404);
            } catch (XmlPullParserException | IOException e) {
                message = e.getMessage();
                e.printStackTrace();
            } catch (InterruptedException e) {
                // Thread was interrupted during unpair
                message = getResources().getString(R.string.error_interrupted);
            }

            final String toastMessage = message;
            runOnUiThread(() -> Toast.makeText(PcView.this, toastMessage, Toast.LENGTH_LONG).show());
        }).start();
    }

    private void doAppList(ComputerDetails computer, boolean newlyPaired, boolean showHiddenGames) {
        if (computer.state == ComputerDetails.State.OFFLINE) {
            Toast.makeText(PcView.this, getResources().getString(R.string.error_pc_offline), Toast.LENGTH_SHORT).show();
            return;
        }
        if (managerBinder == null) {
            Toast.makeText(PcView.this, getResources().getString(R.string.error_manager_not_running), Toast.LENGTH_LONG).show();
            return;
        }

        Intent i = new Intent(this, AppView.class);
        i.putExtra(AppView.NAME_EXTRA, computer.name);
        i.putExtra(AppView.UUID_EXTRA, computer.uuid);
        i.putExtra(AppView.NEW_PAIR_EXTRA, newlyPaired);
        i.putExtra(AppView.SHOW_HIDDEN_APPS_EXTRA, showHiddenGames);
        
        // 如果activeAddress与默认地址不同，说明用户选择了特定地址，需要传递这个信息
        if (computer.activeAddress != null) {
            i.putExtra(AppView.SELECTED_ADDRESS_EXTRA, computer.activeAddress.address);
            i.putExtra(AppView.SELECTED_PORT_EXTRA, computer.activeAddress.port);
        }
        
        startActivity(i);
    }

    /**
     * 显示地址选择对话框
     */
    private void showAddressSelectionDialog(ComputerDetails computer) {
        AddressSelectionDialog dialog = new AddressSelectionDialog(this, computer, address -> {
            // 使用选中的地址创建临时ComputerDetails对象
            ComputerDetails tempComputer = new ComputerDetails(computer);
            tempComputer.activeAddress = address;

            // 使用选中的地址进入应用列表
            doAppList(tempComputer, false, false);
        });
        
        dialog.show();
    }

    @Override
    public boolean onContextItemSelected(MenuItem item) {
        int position = -1;
        ContextMenuInfo menuInfo = item.getMenuInfo();
        if (menuInfo instanceof AdapterContextMenuInfo) {
            position = ((AdapterContextMenuInfo) menuInfo).position;
        }

        if (position < 0) {
            position = this.selectedPosition;
        }

        if (position < 0) return super.onContextItemSelected(item);

        final ComputerObject computer = (ComputerObject) pcGridAdapter.getItem(position);

        // 添加卡片不显示上下文菜单
        if (PcGridAdapter.isAddComputerCard(computer)) {
            return super.onContextItemSelected(item);
        }
        switch (item.getItemId()) {
            case PAIR_ID:
                doPair(computer.details);
                return true;

            case UNPAIR_ID:
                doUnpair(computer.details);
                return true;

            case WOL_ID:
                doWakeOnLan(computer.details);
                return true;

            case DELETE_ID:
                if (ActivityManager.isUserAMonkey()) {
                    LimeLog.info("Ignoring delete PC request from monkey");
                    return true;
                }
                UiHelper.displayDeletePcConfirmationDialog(this, computer.details, () -> {
                    if (managerBinder == null) {
                        Toast.makeText(PcView.this, getResources().getString(R.string.error_manager_not_running), Toast.LENGTH_LONG).show();
                        return;
                    }
                    removeComputer(computer.details);
                }, null);
                return true;

            case FULL_APP_LIST_ID:
                doAppList(computer.details, false, true);
                return true;

            case RESUME_ID:
                if (managerBinder == null) {
                    Toast.makeText(PcView.this, getResources().getString(R.string.error_manager_not_running), Toast.LENGTH_LONG).show();
                    return true;
                }

                // 尝试获取完整的NvApp对象（包括cmdList）
                NvApp actualApp = getNvAppById(computer.details.runningGameId, computer.details.uuid);
                if (actualApp != null) {
                    ServerHelper.doStart(this, actualApp, computer.details, managerBinder);
                } else {
                    // 如果找不到完整的应用信息，使用基本的NvApp对象作为备用
                    ServerHelper.doStart(this, new NvApp("app", computer.details.runningGameId, false), computer.details, managerBinder);
                }
                return true;

            case QUIT_ID:
                if (managerBinder == null) {
                    Toast.makeText(PcView.this, getResources().getString(R.string.error_manager_not_running), Toast.LENGTH_LONG).show();
                    return true;
                }

                // Display a confirmation dialog first
                UiHelper.displayQuitConfirmationDialog(this, () -> ServerHelper.doQuit(PcView.this, computer.details,
                        new NvApp("app", 0, false), managerBinder, null), null);
                return true;
            
            case SLEEP_ID:
                if (managerBinder == null) {
                    Toast.makeText(PcView.this, getResources().getString(R.string.error_manager_not_running), Toast.LENGTH_LONG).show();
                    return true;
                }

                ServerHelper.pcSleep(PcView.this, computer.details, managerBinder, null);
                return true;
            
            case VIEW_DETAILS_ID:
                Dialog.displayDetailsDialog(PcView.this, getResources().getString(R.string.title_details), computer.details.toString(), false);
                return true;

            case TEST_NETWORK_ID:
                ServerHelper.doNetworkTest(PcView.this);
                return true;

            case IPERF3_TEST_ID:
                try {
                    // 1. 直接在UI线程获取地址对象 (因为此操作不耗时)
                    ComputerDetails.AddressTuple addressTuple = ServerHelper.getCurrentAddressFromComputer(computer.details);

                    // 2. 从对象中提取IP地址字符串
                    String currentIp = addressTuple.address;

                    // 3. 直接创建并显示对话框
                    new Iperf3Tester(PcView.this, currentIp).show();

                } catch (IOException e) {
                    // 捕获因 activeAddress 为 null 导致的异常
                    e.printStackTrace();
                    Toast.makeText(this, getResources().getString(R.string.unable_to_get_pc_address, e.getMessage()), Toast.LENGTH_LONG).show();
                }
                return true;

            case GAMESTREAM_EOL_ID:
                HelpLauncher.launchGameStreamEolFaq(PcView.this);
                return true;

            default:
                return super.onContextItemSelected(item);
        }
    }
    
    /**
     * 一键恢复上一次会话
     * 持续查找主机直到找到有运行游戏的主机为止
     */
    private void restoreLastSession() {
        if (managerBinder == null) {
            Toast.makeText(this, getResources().getString(R.string.error_manager_not_running), Toast.LENGTH_LONG).show();
            return;
        }

        // 持续查找有运行游戏的在线主机（使用原始列表，查找所有主机）
        ComputerDetails targetComputer = null;
        for (int i = 0; i < pcGridAdapter.getRawCount(); i++) {
            ComputerObject computer = pcGridAdapter.getRawItem(i);
            if (computer.details.state == ComputerDetails.State.ONLINE && 
                computer.details.pairState == PairState.PAIRED &&
                computer.details.runningGameId != 0) {
                targetComputer = computer.details;
                break; // 找到有运行游戏的主机就停止查找
            }
        }

        if (targetComputer == null) {
            Toast.makeText(this, getResources().getString(R.string.no_online_computer_with_running_game), Toast.LENGTH_SHORT).show();
            return;
        }

        // 恢复会话
        NvApp actualApp = getNvAppById(targetComputer.runningGameId, targetComputer.uuid);
        if (actualApp != null) {
            Toast.makeText(this, getResources().getString(R.string.restoring_session, targetComputer.name), Toast.LENGTH_SHORT).show();
            ServerHelper.doStart(this, actualApp, targetComputer, managerBinder);
        } else {
            // 使用基本的NvApp对象作为备用
            Toast.makeText(this, getResources().getString(R.string.restoring_session, targetComputer.name), Toast.LENGTH_SHORT).show();
            ServerHelper.doStart(this, new NvApp("app", targetComputer.runningGameId, false), targetComputer, managerBinder);
        }
    }

    /**
     * 根据应用ID获取完整的NvApp对象（包括cmdList）
     * @param appId 应用ID
     * @param uuidString PC的UUID
     * @return 完整的NvApp对象，如果找不到则返回null
     */
    private NvApp getNvAppById(int appId, String uuidString) {
        try {
            // 首先尝试从缓存的应用列表中获取
            String rawAppList = CacheHelper.readInputStreamToString(CacheHelper.openCacheFileForInput(getCacheDir(), "applist", uuidString));
            if (!rawAppList.isEmpty()) {
                List<NvApp> applist = NvHTTP.getAppListByReader(new StringReader(rawAppList));
                for (NvApp app : applist) {
                    if (app.getAppId() == appId) {
                        // 保存这个应用信息到SharedPreferences，供下次使用
                        AppCacheManager cacheManager = new AppCacheManager(this);
                        cacheManager.saveAppInfo(uuidString, app);
                        return app;
                    }
                }
            }
            
            // 如果在应用列表中找不到，尝试从SharedPreferences获取
            AppCacheManager cacheManager = new AppCacheManager(this);
            return cacheManager.getAppInfo(uuidString, appId);
        } catch (IOException | XmlPullParserException e) {
            // 如果读取缓存失败，尝试从SharedPreferences获取
            e.printStackTrace();
            AppCacheManager cacheManager = new AppCacheManager(this);
            return cacheManager.getAppInfo(uuidString, appId);
        }
    }

    private void removeComputer(ComputerDetails details) {
        // 不允许删除添加卡片
        if (PcGridAdapter.ADD_COMPUTER_UUID.equals(details.uuid)) {
            return;
        }

        managerBinder.removeComputer(details);

        new DiskAssetLoader(this).deleteAssetsForComputer(details.uuid);

        // Delete hidden games preference value
        getSharedPreferences(AppView.HIDDEN_APPS_PREF_FILENAME, MODE_PRIVATE)
                .edit()
                .remove(details.uuid)
                .apply();

        // 使用原始列表查找要删除的电脑（不管是否隐藏）
        for (int i = 0; i < pcGridAdapter.getRawCount(); i++) {
            ComputerObject computer = pcGridAdapter.getRawItem(i);

            // 跳过添加卡片
            if (PcGridAdapter.isAddComputerCard(computer)) {
                continue;
            }

            if (details.equals(computer.details)) {
                // Disable or delete shortcuts referencing this PC
                shortcutHelper.disableComputerShortcut(details,
                        getResources().getString(R.string.scut_deleted_pc));

                pcGridAdapter.removeComputer(computer);
                pcGridAdapter.notifyDataSetChanged();

                // 检查是否只剩下添加卡片（使用原始列表）
                int realCount = 0;
                for (int j = 0; j < pcGridAdapter.getRawCount(); j++) {
                    if (!PcGridAdapter.isAddComputerCard(pcGridAdapter.getRawItem(j))) {
                        realCount++;
                    }
                }
                if (realCount == 0) {
                    // Show the "Discovery in progress" view
                    noPcFoundLayout.setVisibility(View.VISIBLE);
                }

                break;
            }
        }
    }
    
    /**
     * 创建并添加"添加电脑"卡片
     */
    private void addAddComputerCard() {
        // 检查是否已经存在添加卡片（使用原始列表，避免过滤问题）
        for (int i = 0; i < pcGridAdapter.getRawCount(); i++) {
            ComputerObject computer = pcGridAdapter.getRawItem(i);
            if (PcGridAdapter.isAddComputerCard(computer)) {
                // 已经存在，不需要重复添加
                return;
            }
        }

        // 创建添加卡片
        ComputerDetails addDetails = new ComputerDetails();
        addDetails.uuid = PcGridAdapter.ADD_COMPUTER_UUID;
        try {
            addDetails.name = getString(R.string.title_add_pc);
        } catch (Exception e) {
            addDetails.name = "添加电脑";
        }
        addDetails.state = ComputerDetails.State.UNKNOWN;

        pcGridAdapter.addComputer(new ComputerObject(addDetails));
        pcGridAdapter.notifyDataSetChanged();

        // 移除"未找到PC"视图
        if (noPcFoundLayout != null) {
            noPcFoundLayout.setVisibility(View.INVISIBLE);
        }
    }

    /**
     * 防抖刷新：合并短时间内的多次刷新请求
     */
    private void debouncedNotifyDataSetChanged() {
        // 取消之前的刷新请求
        if (pendingRefreshRunnable != null) {
            refreshHandler.removeCallbacks(pendingRefreshRunnable);
        }

        // 创建新的刷新请求
        pendingRefreshRunnable = () -> {
            pcGridAdapter.notifyDataSetChanged();
            pendingRefreshRunnable = null;
        };

        // 延迟执行刷新
        refreshHandler.postDelayed(pendingRefreshRunnable, REFRESH_DEBOUNCE_DELAY);
    }

    private void updateComputer(ComputerDetails details) {
        // 忽略添加卡片
        if (PcGridAdapter.ADD_COMPUTER_UUID.equals(details.uuid)) {
            return;
        }

        ComputerObject existingEntry = null;

        // 使用原始列表查找，避免过滤导致的重复添加问题
        for (int i = 0; i < pcGridAdapter.getRawCount(); i++) {
            ComputerObject computer = pcGridAdapter.getRawItem(i);

            // 跳过添加卡片
            if (PcGridAdapter.isAddComputerCard(computer)) {
                continue;
            }

            // Check if this is the same computer
            if (details.uuid != null && details.uuid.equals(computer.details.uuid)) {
                existingEntry = computer;
                break;
            }
        }

        if (existingEntry != null) {
            // Replace the information in the existing entry
            existingEntry.details = details;
            // 重新排序，因为状态可能改变（如从未配对变为已配对）
            pcGridAdapter.resort();
        }
        else {
            // Add a new entry
            ComputerObject newComputer = new ComputerObject(details);
            pcGridAdapter.addComputer(newComputer);

            // 检查新添加的设备是否是未配对的
            boolean isUnpaired = details.state == ComputerDetails.State.ONLINE
                    && details.pairState == PairingManager.PairState.NOT_PAIRED;

            // 如果当前隐藏了未配对设备，且新设备是未配对的，自动显示未配对设备
            if (isUnpaired && !pcGridAdapter.isShowUnpairedDevices()) {
                pcGridAdapter.setShowUnpairedDevices(true);

                // 更新按钮图标
                ImageButton toggleUnpairedButton = findViewById(R.id.toggleUnpairedButton);
                if (toggleUnpairedButton != null) {
                    updateToggleUnpairedButtonIcon(toggleUnpairedButton);
                }

                // 显示提示信息
                Toast.makeText(this, getString(R.string.new_unpaired_device_shown), Toast.LENGTH_LONG).show();
            }

            // Remove the "Discovery in progress" view
            noPcFoundLayout.setVisibility(View.INVISIBLE);
            // 添加新条目时触发动画（但第一次加载时不触发，避免重复）
            if (pcListView != null && !isFirstLoad) {
                pcListView.scheduleLayoutAnimation();
            }
        }

        // 使用防抖刷新，避免频繁刷新
        debouncedNotifyDataSetChanged();
    }

    @Override
    public int getAdapterFragmentLayoutId() {
        return R.layout.pc_grid_view;
    }

    @Override
    public void receiveAbsListView(View view) {
        // Generalized interface implementation
        receiveAdapterView(view);
    }

    public void receiveAdapterView(View view) {
        if (view instanceof androidx.recyclerview.widget.RecyclerView) {
            // Update selectionAnimator's RecyclerView and Adapter references
        }
        else if (view instanceof AbsListView) {
            AbsListView listView = (AbsListView) view;
            // 保存引用以便后续触发动画
            pcListView = listView;
            // 移除系统默认的选择背景，使用自定义的 selector
            listView.setSelector(android.R.color.transparent);
            listView.setAdapter(pcGridAdapter);

            // 设置排序动画
            android.view.animation.Animation animation = AnimationUtils.loadAnimation(this, R.anim.pc_grid_item_sort);
            LayoutAnimationController controller = new LayoutAnimationController(animation, 0.12f);
            controller.setOrder(LayoutAnimationController.ORDER_NORMAL);
            listView.setLayoutAnimation(controller);

            // 第一次进入时，先隐藏列表，然后延迟触发动画
            if (isFirstLoad) {
                listView.setAlpha(0f);
                // 延迟触发动画，等待数据准备完成
                listView.postDelayed(() -> {
                    if (isFirstLoad && pcListView != null && pcListView.getAlpha() == 0f) {
                        // 确保数据已刷新
                        pcGridAdapter.notifyDataSetChanged();
                        // 触发动画
                        pcListView.scheduleLayoutAnimation();
                        pcListView.animate()
                                .alpha(1f)
                                .setDuration(200)
                                .start();
                        isFirstLoad = false;
                    }
                }, 250); // 延迟250ms，确保数据已准备好
            }

            listView.setOnItemClickListener((arg0, arg1, pos, id) -> {
                ComputerObject computer = (ComputerObject) pcGridAdapter.getItem(pos);

                if (PcGridAdapter.isAddComputerCard(computer)) {
                    Intent i = new Intent(PcView.this, AddComputerManually.class);
                    startActivity(i);
                    return;
                }

                if (computer.details.state == ComputerDetails.State.UNKNOWN ||
                    computer.details.state == ComputerDetails.State.OFFLINE) {
                    // Open the context menu if a PC is offline or refreshing
                    openContextMenu(arg1);
                } else if (computer.details.pairState != PairState.PAIRED) {
                    // Pair an unpaired machine by default
                    doPair(computer.details);
                } else {
                    // 检查是否有多个可用地址
                    if (computer.details.hasMultipleAddresses()) {
                        showAddressSelectionDialog(computer.details);
                    } else {
                        doAppList(computer.details, false, false);
                    }
                }
            });

            // 如果是GridView，动态计算列宽以保持固定间距
            if (view instanceof GridView) {
                calculateDynamicColumnWidth((GridView) view);
            }

            UiHelper.applyStatusBarPadding(listView);
            registerForContextMenu(listView);
        }
    }

    /**
     * 动态计算GridView的列宽，确保卡片间距保持不变
     * 根据屏幕宽度和固定间距自动调整列宽
     */
    private void calculateDynamicColumnWidth(GridView gridView) {
        float density = getResources().getDisplayMetrics().density;
        int screenWidth = getResources().getDisplayMetrics().widthPixels;

        // 获取可用宽度（扣除左右padding）
        int availableWidth = screenWidth - gridView.getPaddingStart() - gridView.getPaddingEnd();

        // 固定参数（dp转px）
        int horizontalSpacingPx = (int) (15f * density);
        int minColumnWidthPx = (int) (180f * density);

        // 计算列数: numColumns = (availableWidth + spacing) / (minWidth + spacing)
        int numColumns = Math.max(1, (availableWidth + horizontalSpacingPx) / (minColumnWidthPx + horizontalSpacingPx));

        // 计算实际列宽: columnWidth = (availableWidth - (numColumns - 1) * spacing) / numColumns
        int columnWidth = (availableWidth - (numColumns - 1) * horizontalSpacingPx) / numColumns;

        gridView.setColumnWidth(columnWidth);
    }

    public static class ComputerObject {
        public ComputerDetails details;

        public ComputerObject(ComputerDetails details) {
            if (details == null) {
                throw new IllegalArgumentException("details must not be null");
            }
            this.details = details;
        }

        @Override
        public String toString() {
            return details.name;
        }
    }

    private void showAboutDialog() {
        // 创建自定义布局
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_about, null);

        // 设置版本信息
        TextView versionText = dialogView.findViewById(R.id.text_version);
        String versionInfo = getVersionInfo();
        versionText.setText(versionInfo);

        // 设置应用名称
        TextView appNameText = dialogView.findViewById(R.id.text_app_name);
        String appName = getAppName();
        appNameText.setText(appName);

        // 设置描述信息
        TextView descriptionText = dialogView.findViewById(R.id.text_description);
        descriptionText.setText(R.string.about_dialog_description);

        // 创建对话框，使用优雅的样式
        AlertDialog.Builder builder = new AlertDialog.Builder(this, R.style.AppDialogStyle);
        builder.setView(dialogView);

        // 设置按钮
        builder.setPositiveButton(R.string.about_dialog_github, (dialog, which) -> {
            // 打开项目仓库
            openUrl("https://github.com/chenx-dust/moonlight-vminus");
        });

        builder.setNegativeButton(R.string.about_dialog_close, (dialog, which) -> {
            dialog.dismiss();
        });

        // 显示对话框
        AlertDialog dialog = builder.create();
        dialog.show();
    }

    @SuppressLint("DefaultLocale")
    private String getVersionInfo() {
        try {
            PackageInfo packageInfo = getPackageManager()
                    .getPackageInfo(getPackageName(), 0);
            return String.format("Version %s (Build %d)",
                    packageInfo.versionName,
                    packageInfo.versionCode);
        } catch (PackageManager.NameNotFoundException e) {
            return "Version Unknown";
        }
    }

    private String getAppName() {
        try {
            PackageInfo packageInfo = getPackageManager()
                    .getPackageInfo(getPackageName(), 0);
            return packageInfo.applicationInfo.loadLabel(getPackageManager()).toString();
        } catch (PackageManager.NameNotFoundException e) {
            return "Moonlight V-";
        }
    }

    private void openUrl(String url) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Exception e) {
            // 如果无法打开链接，忽略错误
        }
    }
}
