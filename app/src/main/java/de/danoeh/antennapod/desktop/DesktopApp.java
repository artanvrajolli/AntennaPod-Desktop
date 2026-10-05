package de.danoeh.antennapod.desktop;

import de.danoeh.antennapod.model.feed.Chapter;
import de.danoeh.antennapod.model.feed.Feed;
import de.danoeh.antennapod.model.feed.FeedItem;
import de.danoeh.antennapod.model.feed.FeedMedia;
import de.danoeh.antennapod.net.discovery.CombinedSearcher;
import de.danoeh.antennapod.net.discovery.PodcastSearchResult;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javafx.animation.FadeTransition;
import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.Slider;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputControl;
import javafx.scene.control.ToolBar;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.web.WebView;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.util.Duration;

public class DesktopApp extends Application implements PlaybackManager.Listener,
        EpisodeDownloader.ProgressListener {
    private DesktopDatabase database;
    private FeedUpdater feedUpdater;
    private EpisodeDownloader downloader;
    private EpisodeCache episodeCache;
    private final WindowsTaskbar windowsTaskbar = new WindowsTaskbar();
    private final SmtcManager smtc = new SmtcManager();
    /** The episode the system media card last heard about, so artwork fetches stay with it. */
    private volatile long smtcMediaId = -1;
    private volatile File smtcArtworkFile;
    private MediaKeys mediaKeys;
    /** Set once the media card failed to attach, so the media keys are claimed directly. */
    private boolean smtcUnavailable;
    private PlaybackManager playback;
    private ExecutorService background;
    /**
     * Writes feed preferences one at a time, in order. Each write re-reads the stored prefs and
     * changes only its own fields, so the episode-pane sort box and the feed settings panel,
     * which both save the same row, cannot undo each other.
     */
    private ExecutorService prefsWriter;

    private final ObservableList<Feed> feeds = FXCollections.observableArrayList();
    private final ObservableList<FeedItem> episodes = FXCollections.observableArrayList();
    private final FilteredList<Feed> visibleFeeds = new FilteredList<>(feeds, feed -> true);
    private final FilteredList<FeedItem> visibleEpisodes = new FilteredList<>(episodes, item -> true);
    private ListView<Feed> feedList;
    private ListView<FeedItem> episodeList;
    private TextField feedFilterField;
    private TextField episodeFilterField;
    /** The toolbar's search-or-subscribe field. */
    private TextField addField;
    private Button episodeRefreshButton;
    private ComboBox<EpisodeFilter> episodeStateBox;
    private javafx.animation.Timeline downloadsTicker;
    /** Casting to a DLNA renderer: the server the TV fetches from and the running session. */
    private CastServer castServer;
    private CastSession castSession;
    private boolean castPlaying;
    private HBox castBar;
    private Label castLabel;
    private Button castPlayPauseButton;
    private Button castButton;
    /** Every subscription's tags, as last loaded; read and written on the FX thread only. */
    private final Map<Long, java.util.SortedSet<String>> feedTagMap = new HashMap<>();
    private ComboBox<String> tagBox;
    private boolean tagBoxProgrammatic;
    private static final String ALL_TAGS = Messages.get("feeds.tags.all");
    private VBox sidebar;
    private SplitPane listsSplit;
    private VBox feedPane;
    private VBox episodePane;
    private BorderPane appRoot;
    private boolean dividerDragging;
    private boolean sidebarLayoutAdjusting;
    private int sidebarLayoutRequest;
    private Label sidebarTitle;
    private VBox sidebarContent;
    private Label feedTitleLabel;
    private Label statusLabel;
    /** Seconds a status message stays solid before fading out. */
    private static final int STATUS_FADE_SECONDS = 60;
    /** Status history behind the Settings logs tab; newest first, capped. */
    private static final int MAX_STATUS_LOG = 500;
    private final java.util.Deque<StatusEntry> statusLog = new java.util.ArrayDeque<>();

    private static final class StatusEntry {
        final long timeMs;
        final String text;

        StatusEntry(long timeMs, String text) {
            this.timeMs = timeMs;
            this.text = text;
        }
    }
    private PauseTransition statusFadeDelay;
    private FadeTransition statusFadeOut;
    private Label nowPlayingLabel;
    private ImageView nowPlayingArt;
    private StackPane artPlaceholder;
    private VBox artColumn;
    private Label elapsedLabel;
    private Label totalLabel;
    private Button playPauseButton;
    private Button skipBackButton;
    private Button skipForwardButton;
    private Button muteButton;
    private Button chapterPrevButton;
    private Button chapterNextButton;
    /**
     * The synced position behind the ghost marker, looked up once per episode. It used to be read
     * from the database on every position tick, which put a lock shared with feed refreshes and
     * sync on the JavaFX thread several times a second for the whole of playback.
     */
    private long syncedMarkerItemId = -1;
    private volatile int syncedMarkerPositionMs = -1;
    private Slider seekSlider;
    /** The slider's track node, looked up once the skin exists, so progress can be drawn on it. */
    private Node seekTrack;
    /** The slider's thumb dot, tinted with the same accent as the played run. */
    private Node seekThumb;
    /** What the cache last reported fetched, so the track can be repainted as playback moves. */
    private int lastBufferedMs;
    /** Painted gradient stops, so the track is only restyled when something visibly moved. */
    private double paintedPlayedPercent = -1;
    private double paintedBufferedPercent = -1;
    /**
     * The artwork accent tinting the played run and the thumb, as a hex color. Null while the
     * slider keeps the theme's own blue ({@code -fx-accent}): no artwork, or nothing usable
     * sampled from it yet.
     */
    private String seekAccent;
    /** What the accent was last resolved for; "" while it is the plain theme blue. */
    private String seekAccentUrl = "";
    /** The accent the track was last painted with, so a new cover repaints even at 0%. */
    private String paintedAccent;
    /** The track outline and theme last painted; a theme switch repaints even at 0%. */
    private String paintedOutline;
    private boolean paintedDark;
    /**
     * The playing episode the list was last scrolled to. Scrolling happens once per episode,
     * so pausing or buffering never yanks the list back after the user scrolled away.
     */
    private long lastScrolledMediaId = -1;
    private StackPane ghostMarker;
    /** Dissolves the synced marker away over half a minute after it appears. */
    private FadeTransition ghostFadeOut;
    /** The episode the fade above was armed for; re-armed when it or visibility changes. */
    private long ghostFadeItemId = -1;
    /** The episode already informed and faded out; it stays out of the way until a new one. */
    private long ghostFadedItemId = -1;
    private Slider volumeSlider;
    /** The volume slider's track node, looked up once the skin exists, for its fill. */
    private Node volumeTrack;
    /** Painted volume stop, so the track is only restyled on visible movement. */
    private double paintedVolumePercent = -1;
    /** Theme the volume track was last painted for, so a theme switch repaints it. */
    private boolean paintedVolumeDark;
    private ComboBox<String> speedBox;
    private Button silenceButton;
    private ProgressIndicator loadingSpinner;
    private Scene scene;
    private boolean sliderDragging;
    private long lastProgressRefreshMs;
    private static final String PROJECT_URL = "https://github.com/artanvrajolli/AntennaPod-Desktop";
    /** What the synced-position marker means, shown when hovering it in the seek bar or a row. */
    private static final String SYNCED_TIP = Messages.get("player.synced_position");
    private static final int SYNCED_MARKER_MIN_GAP_MS = 30000;
    /** Edge of the synced marker's square; rotated 45 degrees it reads as a hollow diamond. */
    private static final double SYNCED_MARKER_SIZE = 10;
    /** How long the marker stays up before fading away, so it informs once, then leaves. */
    private static final int SYNCED_MARKER_FADE_SECONDS = 30;
    private static final double SLIDER_THUMB_DIAMETER = 14;
    private static final double ART_COLUMN_WIDTH = 96;
    /** Node properties {@link #showHtml} keeps on a shownotes view. */
    private static final String SHOWN_PAGE = "antennapod.shownPage";
    private static final String LINKS_TO_BROWSER = "antennapod.linksToBrowser";
    private final javafx.beans.property.DoubleProperty loadingPhase =
            new javafx.beans.property.SimpleDoubleProperty(0);
    private javafx.animation.Timeline loadingPulseTimeline;
    private StackPane appShell;
    private Region seekPulse;
    private boolean showRemainingTime;
    private double lastVolume;
    /** Written by the downloader's worker threads and read by the cells on the FX thread. */
    private final Map<Long, Integer> downloadProgress = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<Long, Long> feedLastPlayed = new HashMap<>();
    private final Set<Long> syncedItemIds = new HashSet<>();
    private final java.util.concurrent.atomic.AtomicBoolean syncRunning =
            new java.util.concurrent.atomic.AtomicBoolean();
    private final java.util.concurrent.atomic.AtomicBoolean autoSyncPending =
            new java.util.concurrent.atomic.AtomicBoolean();
    private java.util.concurrent.ScheduledExecutorService autoSyncScheduler;
    private Button syncButton;
    private SyncManager syncManager;
    private SleepTimer sleepTimer;
    private Button sleepButton;
    private Label chapterLabel;
    private ComboBox<String> sortBox;
    private boolean sortBoxProgrammatic;
    private Feed selectedFeed;
    /** The still-loading artwork the seek accent already waits for. */
    private Image seekAccentPendingImage;
    /**
     * Per-feed {unplayed, new} counts and the open feed's synced positions, read off the FX thread.
     * The cells used to query the database for these on every redraw - every few seconds while
     * playing - and waited on the database lock behind refreshes and sync.
     */
    private final Map<Long, int[]> feedCounts = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<Long, Integer> syncedPositions = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicBoolean feedCountsQueued =
            new java.util.concurrent.atomic.AtomicBoolean();
    /** Bumped by every episode load, so only the newest one is shown when loads overlap. */
    private long episodeLoadGeneration;
    private volatile long loadingMediaId = -1;
    /**
     * What the episode cells show as playing, copied from the player on every state change:
     * cells must not call into PlaybackManager, whose lock is held across database writes.
     */
    private long cellCurrentMediaId = -1;
    private boolean cellPlaying;
    private final TrayManager trayManager = new TrayManager();
    private boolean trayActive;
    /** The app's own icon, in every size, kept so the window icon can go back to it. */
    private final List<Image> baseIcons = new ArrayList<>();
    /** The same icons as AWT images, converted once, ready to be drawn on. */
    private final List<java.awt.image.BufferedImage> baseIconImages = new ArrayList<>();
    /** The artwork currently drawn into the window icon; "" while it is the plain app icon. */
    private String taskbarIconArtUrl = "";
    private boolean shuttingDown;
    private Stage mainStage;
    private java.nio.channels.FileChannel instanceLockChannel;
    private java.nio.channels.FileLock instanceLock;
    private java.util.concurrent.ScheduledExecutorService autoRefreshScheduler;
    private java.util.concurrent.ScheduledFuture<?> autoRefreshTask;
    private int scheduledRefreshMinutes;
    /** The proxy settings the HTTP client was last built with. */
    private String appliedProxy;

    public static void main(String[] args) {
        launch(args);
    }

    @Override
    public void start(Stage stage) throws Exception {
        DesktopPreferences.getDataDir().mkdirs();
        DesktopPreferences.getMediaDir().mkdirs();
        DesktopPreferences.getCacheDir().mkdirs();
        DesktopPreferences.getEpisodeCacheDir().mkdirs();
        if (!acquireInstanceLock()) {
            Platform.runLater(() -> {
                Alert alert = new Alert(Alert.AlertType.INFORMATION,
                        Messages.get("app.already_running"));
                alert.setTitle("AntennaPod Desktop");
                alert.setHeaderText(null);
                alert.showAndWait();
                Platform.exit();
            });
            return;
        }
        // a restore chosen in Settings is moved into place now, before the library is opened
        boolean restored = false;
        try {
            restored = ProfileBackup.applyPending(DesktopPreferences.getDataDir());
        } catch (Exception e) {
            setStatus(Messages.format("status.backup.restore_failed", e.getMessage()));
        }
        database = new DesktopDatabase(DesktopPreferences.getDatabaseFile());
        if (restored) {
            int cleared = database.clearMissingDownloads();
            setStatus(cleared > 0
                    ? Messages.format("status.backup.restored_redownload", episodeCountText(cleared))
                    : Messages.get("status.backup.restored"));
        }
        feedUpdater = new FeedUpdater(database);
        try {
            // before the first refresh, so protected feeds are fetched with their login
            feedUpdater.reloadCredentials();
        } catch (Exception e) {
            setStatus(Messages.format("status.feed_logins.load_failed", e.getMessage()));
        }
        downloader = new EpisodeDownloader(database);
        background = Executors.newCachedThreadPool(r -> {
            Thread thread = new Thread(r, "desktop-background");
            thread.setDaemon(true);
            return thread;
        });
        prefsWriter = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "desktop-feed-prefs");
            thread.setDaemon(true);
            return thread;
        });
        playback = new PlaybackManager(database, this);
        SyncManager syncManager = new SyncManager(database, feedUpdater);
        playback.setPlayActionRecorder(media -> {
            syncManager.recordPlayAction(media);
            scheduleAutoSync();
        });
        playback.setAutoDeleteHandler(this::autoDeleteFinished);
        episodeCache = new EpisodeCache(database);
        episodeCache.setProtectedIdsSupplier(this::protectedMediaIds);
        episodeCache.setStatusReporter(this::setStatus);
        playback.setCacheHandlers(this::cachePlaybackStarted, this::cachePlaybackFinished);
        playback.setResumeLastHandler(this::resumeLastPlayed);
        episodeCache.setProgressReporter(this::onCacheProgress);
        background.submit(() -> {
            int swept = episodeCache.sweepFinished();
            if (swept > 0) {
                // a lambda, not episodeList::refresh: that would read the field here, on this
                // thread, possibly before the FX thread has built the list
                Platform.runLater(() -> episodeList.refresh());
            }
        });
        this.syncManager = syncManager;
        scheduleAutoSync();
        sleepTimer = new SleepTimer(() -> {
            // pause only: toggling could start playback if it stopped in the meantime
            playback.pause();
            setStatus(Messages.get("status.sleep.expired"));
        });
        sleepTimer.restore();
        if (sleepTimer.getMode() == SleepTimer.Mode.END_OF_EPISODE) {
            playback.setStopAfterCurrent(true);
        }
        playback.setStopAfterCurrentHandler(() -> {
            // the end-of-episode timer has done its job; left on, it showed "episode" without
            // stopping the next one and came back at the next launch to stop an episode unasked
            if (sleepTimer.getMode() == SleepTimer.Mode.END_OF_EPISODE) {
                sleepTimer.cancel();
                updateSleepButton();
                setStatus(Messages.get("status.sleep.stopped_at_end"));
            }
        });
        javafx.animation.Timeline sleepTicker = new javafx.animation.Timeline(
                new javafx.animation.KeyFrame(javafx.util.Duration.seconds(1),
                        event -> updateSleepButton()));
        sleepTicker.setCycleCount(javafx.animation.Animation.INDEFINITE);
        sleepTicker.play();

        BorderPane root = new BorderPane();
        root.setTop(buildToolbar());
        feedPane = buildFeedPane();
        feedPane.setMinWidth(180);
        episodePane = buildEpisodePane();
        episodePane.setMinWidth(320);
        // the divider between the two lists drags horizontally, so the subscriptions
        // can be widened or narrowed; where it is left is remembered across restarts
        listsSplit = new SplitPane(feedPane, episodePane);
        listsSplit.setDividerPositions(DesktopPreferences.getFeedSplitPosition());
        SplitPane.Divider divider = listsSplit.getDividers().get(0);
        listsSplit.getDividers().get(0).positionProperty().addListener(
                (obs, oldPosition, newPosition) -> {
                    if (dividerDragging && !sidebarLayoutAdjusting) {
                        DesktopPreferences.setFeedSplitPosition(newPosition.doubleValue());
                    }
                });
        listsSplit.addEventHandler(MouseEvent.MOUSE_PRESSED, event -> {
            double dividerX = divider.getPosition() * listsSplit.getWidth();
            if (Math.abs(event.getX() - dividerX) <= 4) {
                dividerDragging = true;
            }
        });
        listsSplit.addEventHandler(MouseEvent.MOUSE_RELEASED, event -> dividerDragging = false);
        listsSplit.addEventHandler(MouseEvent.MOUSE_EXITED, event -> dividerDragging = false);
        appRoot = root;
        root.setCenter(listsSplit);
        root.setRight(buildSidebar());
        root.setBottom(buildPlayerBar());

        appShell = new StackPane(root);
        stage.setTitle(APP_NAME + " " + appVersion());
        stage.setMinWidth(1000);
        stage.setMinHeight(640);
        baseIcons.addAll(appIcons());
        stage.getIcons().addAll(baseIcons);
        javafx.scene.Parent sceneRoot = appShell;
        if (WindowChrome.isEnabled()) {
            // the style has to be set before the stage is shown, and it cannot be changed after
            stage.initStyle(javafx.stage.StageStyle.UNDECORATED);
            // the bar shows the version beside the name, so the title itself carries only the name
            stage.setTitle(APP_NAME);
            sceneRoot = WindowChrome.install(stage, appShell, appVersion());
        }
        scene = new Scene(sceneRoot, 1100, 700);
        ThemeManager.init();
        ThemeManager.style(scene);
        scene.addEventFilter(KeyEvent.KEY_PRESSED, this::handleGlobalKey);
        scene.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == KeyCode.ESCAPE && hasModal()) {
                closeTopModal();
                event.consume();
            }
        });
        stage.setScene(scene);
        nameInputs();
        mainStage = stage;
        stage.setOnCloseRequest(event -> {
            if (trayActive && DesktopPreferences.getCloseToTray()) {
                event.consume();
                stage.hide();
                if (!DesktopPreferences.getTrayHintShown()) {
                    // closing the window keeps playing by default, so say so once rather than
                    // leaving someone to think the app vanished
                    DesktopPreferences.setTrayHintShown(true);
                    trayManager.notify(Messages.format("tray.hint.title", APP_NAME),
                            Messages.get("tray.hint.text"));
                }
            } else {
                shutdown();
            }
        });
        // started with Windows: come up out of the way, in the tray once it exists
        boolean startMinimized = getParameters().getRaw().contains(StartupRegistration.MINIMIZED_ARG);
        if (startMinimized) {
            stage.setIconified(true);
        }
        stage.show();
        // the taskbar button only exists once the window is showing
        windowsTaskbar.attach(stage, new ThumbBar.Callbacks() {
            @Override
            public void onPrevious() {
                playback.playPrevious();
            }

            @Override
            public void onPlayPause() {
                togglePlayPause();
            }

            @Override
            public void onNext() {
                playback.playNext();
            }

            @Override
            public boolean isSilenceSkipping() {
                return playback.isSilenceSkipping();
            }

            @Override
            public void onSilenceSkipping(boolean enabled) {
                setSilenceSkipping(enabled);
            }
        });
        // the episode in the Windows volume flyout, with the same transport vocabulary
        smtc.setErrorReporter(message -> setStatus(Messages.format("status.media_card", message)));
        // with no card the media keys have nothing to arrive through: claim them after all
        smtc.setAttachFailedHandler(() -> Platform.runLater(() -> {
            smtcUnavailable = true;
            if (mediaKeys == null) {
                startMediaKeys();
            }
        }));
        smtc.attach(stage, new SmtcManager.Callbacks() {
            @Override
            public void onPlay() {
                onCardButton(() -> {
                    if (!playback.isPlaying()) {
                        togglePlayPause();
                    }
                });
            }

            @Override
            public void onPause() {
                onCardButton(playback::pause);
            }

            @Override
            public void onStop() {
                onCardButton(playback::stop);
            }

            @Override
            public void onNext() {
                onCardButton(playback::playNext);
            }

            @Override
            public void onPrevious() {
                onCardButton(playback::playPrevious);
            }

            @Override
            public void onSeek(int positionMs) {
                onCardButton(() -> playback.seek(positionMs));
            }
        });
        startMediaKeys();
        boolean trayEnabled = !"false".equalsIgnoreCase(
                System.getProperty("antennapod.desktop.tray", "true"));
        trayActive = trayEnabled && trayManager.init(new TrayManager.Callbacks() {
            @Override
            public void onPlayPause() {
                togglePlayPause();
            }

            @Override
            public void onPrevious() {
                playback.playPrevious();
            }

            @Override
            public void onNext() {
                playback.playNext();
            }

            @Override
            public void onSkipBack() {
                skipBy(-DesktopPreferences.getSkipBackSec() * 1000);
            }

            @Override
            public void onSkipForward() {
                skipBy(DesktopPreferences.getSkipForwardSec() * 1000);
            }

            @Override
            public void onSeek(int positionMs) {
                playback.seek(positionMs);
            }

            @Override
            public void onShow() {
                showMainWindow();
            }

            @Override
            public void onExit() {
                trayManager.remove();
                trayActive = false;
                shutdown();
            }

            @Override
            public boolean isSilenceSkipping() {
                return playback.isSilenceSkipping();
            }

            @Override
            public void onSilenceSkipping(boolean enabled) {
                setSilenceSkipping(enabled);
            }
        });
        Platform.setImplicitExit(!trayActive);

        if (startMinimized) {
            if (trayActive) {
                // the same hide as closing to the tray; the tray icon brings the window back
                stage.hide();
            }
        }
        reloadFeeds(null);
        applyProxy();
        if (DesktopPreferences.getUpdateCheckEnabled()) {
            // quietly: nothing is said unless there is something newer to say it about
            background.submit(() -> checkForUpdates(false));
        }
        scheduleAutoRefresh();
        if (DesktopPreferences.getAutoRefreshStartup()) {
            refreshAll();
        }
    }

    private void applyProxy() {
        String host = DesktopPreferences.getProxyHost();
        // settings save on every change, and rebuilding the HTTP client drops its connections,
        // so only a proxy that actually changed is applied
        String proxy = host + "|" + DesktopPreferences.getProxyPort() + "|"
                + DesktopPreferences.getProxyUser() + "|" + DesktopPreferences.getProxyPassword();
        if (proxy.equals(appliedProxy)) {
            return;
        }
        appliedProxy = proxy;
        if (host == null || host.isEmpty()) {
            de.danoeh.antennapod.net.common.AntennapodHttpClient.setProxyConfig(null);
        } else {
            de.danoeh.antennapod.model.download.ProxyConfig config =
                    new de.danoeh.antennapod.model.download.ProxyConfig(java.net.Proxy.Type.HTTP, host,
                            DesktopPreferences.getProxyPort() > 0 ? DesktopPreferences.getProxyPort() : 8080,
                            DesktopPreferences.getProxyUser(), DesktopPreferences.getProxyPassword());
            de.danoeh.antennapod.net.common.AntennapodHttpClient.setProxyConfig(config);
        }
        de.danoeh.antennapod.net.common.AntennapodHttpClient.reinit();
    }

    private synchronized void scheduleAutoRefresh() {
        int minutes = DesktopPreferences.getAutoRefreshMinutes();
        if (autoRefreshTask != null && minutes == scheduledRefreshMinutes) {
            // settings save on every change; restarting the timer each time kept pushing the
            // next refresh back by a whole interval
            return;
        }
        if (autoRefreshTask != null) {
            autoRefreshTask.cancel(false);
            autoRefreshTask = null;
        }
        scheduledRefreshMinutes = minutes;
        if (minutes <= 0) {
            return;
        }
        if (autoRefreshScheduler == null) {
            autoRefreshScheduler = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                Thread thread = new Thread(r, "auto-refresh");
                thread.setDaemon(true);
                return thread;
            });
        }
        autoRefreshTask = autoRefreshScheduler.scheduleWithFixedDelay(() -> {
            try {
                List<FeedUpdater.RefreshResult> results = feedUpdater.refreshAll();
                int total = 0;
                for (FeedUpdater.RefreshResult result : results) {
                    if (result.error == null) {
                        total += result.newEpisodes.size();
                        autoDownloadNew(result.feed, result.newEpisodes);
                    }
                }
                setStatus(Messages.format("status.autorefresh.done", total));
                notifyNewEpisodes(results);
                refreshFeedCounts();
                Platform.runLater(() -> {
                    Feed selected = feedList.getSelectionModel().getSelectedItem();
                    if (selected != null) {
                        loadEpisodes(selected);
                    }
                });
            } catch (Exception e) {
                setStatus(Messages.format("status.autorefresh.failed", e.getMessage()));
            }
        }, minutes, minutes, java.util.concurrent.TimeUnit.MINUTES);
    }

    private ToolBar buildToolbar() {
        // one field for both ways of adding a podcast: an address subscribes, anything else searches
        TextField inputField = new TextField();
        addField = inputField;
        inputField.setPromptText(Messages.get("toolbar.search.prompt"));
        inputField.setPrefWidth(340);
        Button goButton = new Button(Messages.get("toolbar.search"), Icons.search());
        // sized for the wider label so the toolbar does not shift as the label flips while typing;
        // measured once the skin exists, since an unskinned button has no preferred width yet
        goButton.skinProperty().addListener((obs, oldSkin, skin) -> {
            if (skin != null && goButton.getMinWidth() == Region.USE_COMPUTED_SIZE) {
                String shown = goButton.getText();
                goButton.setText(Messages.get("common.subscribe"));
                double wide = goButton.prefWidth(-1);
                goButton.setText(shown);
                goButton.setMinWidth(Math.max(wide, goButton.prefWidth(-1)));
            }
        });
        inputField.textProperty().addListener((obs, oldText, newText) -> {
            boolean url = FeedInput.looksLikeFeedUrl(newText);
            goButton.setText(url ? Messages.get("common.subscribe") : Messages.get("toolbar.search"));
            goButton.setGraphic(url ? Icons.add() : Icons.search());
        });
        Runnable go = () -> {
            // the button is disabled while a subscribe or search runs; Enter must not start another
            if (goButton.isDisabled()) {
                return;
            }
            String text = inputField.getText();
            if (FeedInput.looksLikeFeedUrl(text)) {
                startSubscribe(goButton, text);
            } else {
                startSearch(goButton, text);
            }
        };
        goButton.setOnAction(event -> go.run());
        inputField.setOnAction(event -> go.run());
        Button refreshAllButton = new Button(Messages.get("toolbar.refresh_all"), Icons.refresh());
        refreshAllButton.setOnAction(event -> {
            setStatus(Messages.get("status.refresh.all_started"));
            spinWhile(refreshAllButton, this::doRefreshAll);
        });
        Button syncButton = new Button(Messages.get("toolbar.sync"), Icons.sync());
        this.syncButton = syncButton;
        updateSyncButtonTooltip();
        syncButton.setOnAction(event -> showSyncDialog());
        // the everyday views live one click away under Library; the occasional
        // actions under More — fourteen top-level controls was a wall of buttons
        MenuButton libraryMenu = new MenuButton(Messages.get("toolbar.library"), Icons.queue());
        libraryMenu.getItems().addAll(
                toolbarMenuItem(Messages.get("menu.library.queue"), Icons.queue(), this::showQueue),
                toolbarMenuItem(Messages.get("menu.library.favorites"), Icons.favorite(), this::showFavorites),
                toolbarMenuItem(Messages.get("menu.library.downloads"), Icons.download(), this::showDownloads),
                toolbarMenuItem(Messages.get("menu.library.search_episodes"), Icons.search(), this::showEpisodeSearch),
                toolbarMenuItem(Messages.get("menu.library.history"), Icons.history(), this::showHistory),
                toolbarMenuItem(Messages.get("menu.library.stats"), Icons.stats(), this::showStatistics));
        MenuButton moreMenu = new MenuButton(Messages.get("toolbar.more"), Icons.more());
        moreMenu.getItems().addAll(
                toolbarMenuItem(Messages.get("menu.more.mark_all_seen"), Icons.check(), this::markAllSeen),
                toolbarMenuItem(Messages.get("menu.more.mini_player"), Icons.play(), this::showMiniPlayer),
                toolbarMenuItem(Messages.get("menu.more.add_local_folder"), Icons.folder(), this::addLocalFolder),
                toolbarMenuItem(Messages.get("menu.more.import"), Icons.download(), this::importOpml),
                toolbarMenuItem(Messages.get("menu.more.export"), Icons.upload(), this::exportOpml),
                toolbarMenuItem(Messages.get("menu.more.settings"), Icons.settings(), this::showSettings),
                toolbarMenuItem(Messages.get("menu.more.project_page"), Icons.github(), this::openProjectPage));
        if (isDevBuild()) {
            // helpers for running from source: never part of an installed build
            moreMenu.getItems().addAll(new SeparatorMenuItem(),
                    toolbarMenuItem(Messages.get("menu.more.dev_reload"), Icons.refresh(), this::reloadAll),
                    toolbarMenuItem(Messages.get("menu.more.dev_open_project"), Icons.folder(), this::openProjectDir));
        }
        // inputs stay left, actions sit at the far right
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        return new ToolBar(inputField, goButton, spacer, refreshAllButton, syncButton, libraryMenu, moreMenu);
    }

    private static MenuItem toolbarMenuItem(String text, Node icon, Runnable action) {
        MenuItem item = new MenuItem(text, icon);
        item.setOnAction(event -> action.run());
        return item;
    }

    /**
     * Runs background work with a spinner in the button that started it, so long operations
     * show on the control itself rather than only as a status line.
     */
    private void spinWhile(Button button, Runnable work) {
        Node original = showButtonSpinner(button);
        background.submit(() -> {
            try {
                work.run();
            } finally {
                Platform.runLater(() -> hideButtonSpinner(button, original));
            }
        });
    }

    private static Node showButtonSpinner(Button button) {
        Node original = button.getGraphic();
        ProgressIndicator spinner = new ProgressIndicator(-1);
        spinner.setPrefSize(16, 16);
        spinner.setMaxSize(16, 16);
        button.setGraphic(spinner);
        button.setDisable(true);
        return original;
    }

    private static void hideButtonSpinner(Button button, Node original) {
        button.setGraphic(original);
        button.setDisable(false);
    }

    private VBox buildFeedPane() {
        feedFilterField = new TextField();
        feedFilterField.setPromptText(Messages.get("feeds.search.prompt"));
        feedFilterField.textProperty().addListener((obs, oldText, newText) -> applyFeedFilter());
        // only there once some subscription has a tag
        tagBox = new ComboBox<>();
        tagBox.setMaxWidth(Double.MAX_VALUE);
        tagBox.setTooltip(new Tooltip(Messages.get("feeds.tags.tooltip")));
        tagBox.setVisible(false);
        tagBox.setManaged(false);
        tagBox.setOnAction(event -> {
            if (tagBoxProgrammatic) {
                return;
            }
            String chosen = ALL_TAGS.equals(tagBox.getValue()) ? "" : tagBox.getValue();
            prefsWriter.submit(() -> DesktopPreferences.setFeedTagFilter(chosen));
            applyFeedFilter();
        });
        feedList = new ListView<>(visibleFeeds);
        feedList.getSelectionModel().setSelectionMode(SelectionMode.SINGLE);
        feedList.setPrefWidth(280);
        feedList.setCellFactory(list -> new FeedCell());
        feedList.getSelectionModel().selectedItemProperty().addListener((obs, oldFeed, newFeed) -> {
            if (newFeed != null && (selectedFeed == null || selectedFeed.getId() != newFeed.getId())) {
                // same subscription re-selected after a background reload keeps showing
                // what it shows; only a different one loads
                loadEpisodes(newFeed);
            }
        });
        feedList.setOnMouseClicked(event -> {
            if (event.getClickCount() == 2) {
                Feed selected = feedList.getSelectionModel().getSelectedItem();
                if (selected != null) {
                    playFeed(selected);
                }
            }
        });
        // refreshing the open subscription lives in the episode header, next to its list
        Button settingsButton = new Button(Messages.get("feeds.settings"));
        settingsButton.setOnAction(event -> {
            Feed selected = feedList.getSelectionModel().getSelectedItem();
            if (selected != null) {
                showFeedSettings(selected);
            }
        });
        HBox buttons = new HBox(8, settingsButton);
        buttons.setPadding(new Insets(8));
        VBox pane = new VBox(4, new Label(Messages.get("feeds.title")), tagBox, feedFilterField, feedList, buttons);
        pane.setPadding(new Insets(8));
        VBox.setVgrow(feedList, Priority.ALWAYS);
        return pane;
    }

    private javafx.scene.control.ContextMenu buildFeedContextMenu(Feed feed) {
        javafx.scene.control.ContextMenu menu = new javafx.scene.control.ContextMenu();
        javafx.scene.control.MenuItem refresh =
                new javafx.scene.control.MenuItem(Messages.get("feeds.menu.refresh"));
        refresh.setOnAction(event -> refreshFeed(feed));
        javafx.scene.control.MenuItem markSeen =
                new javafx.scene.control.MenuItem(Messages.get("feeds.menu.mark_seen"));
        markSeen.setOnAction(event -> markFeedSeen(feed));
        javafx.scene.control.MenuItem settings =
                new javafx.scene.control.MenuItem(Messages.get("feeds.settings"));
        settings.setOnAction(event -> showFeedSettings(feed));
        javafx.scene.control.MenuItem tags = new javafx.scene.control.MenuItem(Messages.get("feeds.menu.tags"));
        tags.setOnAction(event -> showFeedTagsModal(feed));
        javafx.scene.control.MenuItem unsubscribe =
                new javafx.scene.control.MenuItem(Messages.get("feeds.menu.unsubscribe"));
        unsubscribe.setStyle("-fx-text-fill: #d9534f;");
        unsubscribe.setOnAction(event -> unsubscribe(feed));
        menu.getItems().addAll(refresh, markSeen, settings, tags,
                new javafx.scene.control.SeparatorMenuItem(), unsubscribe);
        return menu;
    }

    private javafx.scene.control.ContextMenu buildEpisodeContextMenu(FeedItem item) {
        List<FeedItem> targets = actionTargets(item);
        javafx.scene.control.ContextMenu menu = new javafx.scene.control.ContextMenu();
        javafx.scene.control.MenuItem markPlayed =
                new javafx.scene.control.MenuItem(Messages.get("episodes.menu.mark_played"));
        markPlayed.setOnAction(event -> applyPlayedState(targets, true));
        javafx.scene.control.MenuItem markUnplayed =
                new javafx.scene.control.MenuItem(Messages.get("episodes.menu.mark_unplayed"));
        markUnplayed.setOnAction(event -> applyPlayedState(targets, false));
        javafx.scene.control.MenuItem addToQueue =
                new javafx.scene.control.MenuItem(Messages.get("episodes.menu.add_to_queue"));
        addToQueue.setOnAction(event -> enqueueItems(targets));
        javafx.scene.control.MenuItem removeFromQueue =
                new javafx.scene.control.MenuItem(Messages.get("episodes.menu.remove_from_queue"));
        removeFromQueue.setOnAction(event -> dequeueItems(targets));
        javafx.scene.control.MenuItem addFavorite =
                new javafx.scene.control.MenuItem(Messages.get("episodes.menu.add_favorite"));
        addFavorite.setOnAction(event -> setFavorites(targets, true));
        javafx.scene.control.MenuItem removeFavorite =
                new javafx.scene.control.MenuItem(Messages.get("episodes.menu.remove_favorite"));
        removeFavorite.setOnAction(event -> setFavorites(targets, false));
        javafx.scene.control.MenuItem download =
                new javafx.scene.control.MenuItem(Messages.get("episodes.menu.download"));
        download.setOnAction(event -> enqueueDownloads(targets));
        download.setDisable(!hasDownloadable(targets));
        javafx.scene.control.MenuItem deleteDownload =
                new javafx.scene.control.MenuItem(Messages.get("episodes.menu.delete_download"));
        deleteDownload.setOnAction(event -> deleteDownloads(targets));
        deleteDownload.setDisable(!hasDownloaded(targets));
        menu.getItems().addAll(markPlayed, markUnplayed,
                new javafx.scene.control.SeparatorMenuItem(), addToQueue, removeFromQueue,
                new javafx.scene.control.SeparatorMenuItem(), addFavorite, removeFavorite,
                new javafx.scene.control.SeparatorMenuItem(), download, deleteDownload);
        return menu;
    }

    private void applyFeedFilter() {
        String query = feedFilterField.getText().trim().toLowerCase(Locale.ROOT);
        String tag = tagBox != null && tagBox.isVisible() && tagBox.getValue() != null
                && !ALL_TAGS.equals(tagBox.getValue()) ? tagBox.getValue() : null;
        // a new predicate clears the list's selection; the open subscription stays selected
        // whenever it is still shown
        Feed selected = feedList.getSelectionModel().getSelectedItem();
        visibleFeeds.setPredicate(feed -> (tag == null || hasTag(feed, tag))
                && (query.isEmpty() || feedSearchText(feed).contains(query)));
        if (selected != null && visibleFeeds.contains(selected)
                && feedList.getSelectionModel().getSelectedItem() != selected) {
            feedList.getSelectionModel().select(selected);
        }
    }

    private boolean hasTag(Feed feed, String tag) {
        java.util.SortedSet<String> tags = feedTagMap.get(feed.getId());
        return tags != null && tags.contains(tag);
    }

    /** Every tag in use, sorted, ignoring case. */
    private java.util.SortedSet<String> allTags() {
        java.util.SortedSet<String> all = new java.util.TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (java.util.SortedSet<String> tags : feedTagMap.values()) {
            all.addAll(tags);
        }
        return all;
    }

    /** Takes freshly loaded tags: the picker lists them and keeps (or restores) its choice. */
    private void applyFeedTags(Map<Long, java.util.SortedSet<String>> tags) {
        feedTagMap.clear();
        feedTagMap.putAll(tags);
        java.util.SortedSet<String> all = allTags();
        String wanted = tagBox.getValue() != null ? tagBox.getValue() : DesktopPreferences.getFeedTagFilter();
        tagBoxProgrammatic = true;
        try {
            List<String> choices = new ArrayList<>();
            choices.add(ALL_TAGS);
            choices.addAll(all);
            tagBox.getItems().setAll(choices);
            tagBox.setValue(all.contains(wanted) ? all.tailSet(wanted).first() : ALL_TAGS);
        } finally {
            tagBoxProgrammatic = false;
        }
        tagBox.setVisible(!all.isEmpty());
        tagBox.setManaged(!all.isEmpty());
        applyFeedFilter();
    }

    private void reloadFeedTags() {
        background.submit(() -> {
            try {
                Map<Long, java.util.SortedSet<String>> tags = database.getFeedTags();
                Platform.runLater(() -> applyFeedTags(tags));
            } catch (Exception e) {
                setStatus(Messages.format("status.tags.load_failed", e.getMessage()));
            }
        });
    }

    /** Ticks a subscription's tags on and off, or adds new ones. */
    private void showFeedTagsModal(Feed feed) {
        java.util.SortedSet<String> current = feedTagMap.getOrDefault(feed.getId(),
                new java.util.TreeSet<>(String.CASE_INSENSITIVE_ORDER));
        VBox boxes = new VBox(6);
        List<javafx.scene.control.CheckBox> checks = new ArrayList<>();
        java.util.function.Consumer<String> addCheck = tag -> {
            for (javafx.scene.control.CheckBox check : checks) {
                if (check.getText().equalsIgnoreCase(tag)) {
                    check.setSelected(true);
                    return;
                }
            }
            javafx.scene.control.CheckBox check = new javafx.scene.control.CheckBox(tag);
            check.setSelected(current.contains(tag) || !allTags().contains(tag));
            checks.add(check);
            boxes.getChildren().add(check);
        };
        allTags().forEach(addCheck);
        Label none = new Label(Messages.get("tags.none"));
        none.getStyleClass().add("muted-label");
        none.setVisible(checks.isEmpty());
        none.setManaged(checks.isEmpty());
        TextField newTag = new TextField();
        newTag.setPromptText(Messages.get("tags.new.prompt"));
        Button add = new Button(Messages.get("tags.add"), Icons.add());
        Runnable addNew = () -> {
            String tag = newTag.getText().trim();
            if (!tag.isEmpty()) {
                addCheck.accept(tag);
                newTag.clear();
                none.setVisible(false);
                none.setManaged(false);
            }
            newTag.requestFocus();
        };
        add.setOnAction(event -> addNew.run());
        newTag.setOnAction(event -> addNew.run());
        HBox.setHgrow(newTag, Priority.ALWAYS);
        Button save = new Button(Messages.get("common.save"));
        save.setDefaultButton(true);
        VBox pane = new VBox(10, boxes, none, new HBox(8, newTag, add));
        save.setOnAction(event -> {
            List<String> chosen = new ArrayList<>();
            for (javafx.scene.control.CheckBox check : checks) {
                if (check.isSelected()) {
                    chosen.add(check.getText());
                }
            }
            appShell.getChildren().remove(modalOverlayOf(pane));
            background.submit(() -> {
                try {
                    database.setFeedTags(feed.getId(), chosen);
                    setStatus(chosen.isEmpty() ? Messages.format("status.tags.removed", feed.getTitle())
                            : Messages.format("status.tags.saved", feed.getTitle(), String.join(", ", chosen)));
                    reloadFeedTags();
                } catch (Exception e) {
                    setStatus(Messages.format("status.tags.save_failed", e.getMessage()));
                }
            });
        });
        HBox buttons = new HBox(8, save);
        buttons.setAlignment(Pos.CENTER_RIGHT);
        pane.getChildren().add(buttons);
        pane.setPadding(new Insets(12));
        showModal(Messages.format("tags.title",
                feed.getTitle() != null ? feed.getTitle() : feed.getDownloadUrl()), pane);
        Platform.runLater(newTag::requestFocus);
    }

    private static String feedSearchText(Feed feed) {
        String text = feed.getTitle();
        if (text == null || text.isEmpty()) {
            text = feed.getDownloadUrl();
        }
        return text == null ? "" : text.toLowerCase(Locale.ROOT);
    }

    private static <T> ListCell<T> fullWidthCell(ListCell<T> cell) {
        cell.setPrefWidth(0);
        return cell;
    }

    private VBox buildSidebar() {
        sidebarTitle = new Label();
        sidebarTitle.getStyleClass().add("sidebar-title");
        sidebarTitle.setMaxWidth(Double.MAX_VALUE);
        Button closeButton = iconButton(Icons.remove(), Messages.get("common.close_panel"));
        closeButton.getStyleClass().add("flat");
        closeButton.setOnAction(event -> hideSidebar());
        HBox header = new HBox(8, sidebarTitle, closeButton);
        header.getStyleClass().add("sidebar-header");
        header.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(sidebarTitle, Priority.ALWAYS);
        HBox.setMargin(closeButton, new Insets(0, 0, 0, 8));
        sidebarContent = new VBox();
        sidebarContent.getStyleClass().add("sidebar-content");
        VBox.setVgrow(sidebarContent, Priority.ALWAYS);
        sidebar = new VBox(header, sidebarContent);
        sidebar.getStyleClass().add("sidebar");
        sidebar.setPrefWidth(460);
        sidebar.setMinWidth(340);
        sidebar.setVisible(false);
        sidebar.setManaged(false);
        return sidebar;
    }

    private void showSidebar(String title, Node content) {
        sidebarTitle.setText(title);
        sidebarContent.getChildren().setAll(content);
        if (content instanceof Region) {
            VBox.setVgrow(content, Priority.ALWAYS);
        }
        preserveFeedWidthBeforeSidebarChange();
        sidebar.setVisible(true);
        sidebar.setManaged(true);
    }

    static void restoreFeedWidth(SplitPane listsSplit, Region feedPane, Region episodePane,
            double feedWidth) {
        if (listsSplit == null || feedPane == null || episodePane == null || feedWidth <= 0) {
            return;
        }
        double splitWidth = listsSplit.getWidth();
        if (splitWidth <= 0) {
            return;
        }
        double dividerWidth = Math.max(0,
                splitWidth - feedPane.getWidth() - episodePane.getWidth());
        double available = splitWidth - dividerWidth;
        if (available <= 0) {
            return;
        }
        double minimum = feedPane.getMinWidth();
        double maximum = Math.max(minimum, available - episodePane.getMinWidth());
        double target = Math.max(minimum, Math.min(maximum, feedWidth));
        // divider positions are fractions of the full split width, while the feed pane ends
        // half a divider short of its stop - dividing by the divider-less width undershot by
        // ~2px per toggle and the list kept shrinking with every sidebar open/close
        listsSplit.setDividerPositions((target + dividerWidth / 2) / splitWidth);
    }

    private void preserveFeedWidthBeforeSidebarChange() {
        if (listsSplit == null || feedPane == null || episodePane == null) {
            return;
        }
        double feedWidth = feedPane.getWidth();
        if (feedWidth <= 0) {
            double available = listsSplit.getWidth();
            if (available > 0) {
                feedWidth = listsSplit.getDividers().get(0).getPosition() * available;
            }
        }
        final double preservedWidth = feedWidth;
        sidebarLayoutAdjusting = true;
        int request = ++sidebarLayoutRequest;
        Platform.runLater(() -> {
            if (request == sidebarLayoutRequest && appRoot != null) {
                try {
                    appRoot.applyCss();
                    appRoot.layout();
                    restoreFeedWidth(listsSplit, feedPane, episodePane, preservedWidth);
                } finally {
                    sidebarLayoutAdjusting = false;
                }
            } else {
                sidebarLayoutAdjusting = false;
            }
        });
    }

    private void showModal(String title, Node content) {
        showModal(title, content, false);
    }

    /**
     * @param scrollsItself true for content with scrolling of its own (Settings: one scroll
     *                      pane per tab); it then gets no second scroll pane around it, only
     *                      the height cap, which is what showed two scroll bars side by side
     */
    private void showModal(String title, Node content, boolean scrollsItself) {
        Label modalTitle = new Label(title);
        modalTitle.getStyleClass().add("sidebar-title");
        modalTitle.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(modalTitle, Priority.ALWAYS);
        Button closeButton = iconButton(Icons.remove(), Messages.get("common.close"));
        closeButton.getStyleClass().add("flat");
        HBox header = new HBox(8, modalTitle, closeButton);
        header.getStyleClass().add("sidebar-header");
        header.setAlignment(Pos.CENTER_LEFT);
        HBox.setMargin(closeButton, new Insets(0, 0, 0, 8));

        VBox contentBox = new VBox(content);
        contentBox.getStyleClass().add("sidebar-content");
        VBox.setVgrow(content, Priority.ALWAYS);
        Region body;
        if (scrollsItself) {
            contentBox.maxHeightProperty().bind(appShell.heightProperty().subtract(160));
            body = contentBox;
        } else {
            ScrollPane scroller = new ScrollPane(contentBox);
            scroller.setFitToWidth(true);
            // content that fits fills the viewport exactly, so no scroll bar appears for a
            // pixel of rounding; taller content (min height above the viewport) still scrolls
            scroller.setFitToHeight(true);
            scroller.getStyleClass().add("modal-scroll");
            StackPane hinted = withScrollHints(scroller);
            hinted.maxHeightProperty().bind(appShell.heightProperty().subtract(160));
            body = hinted;
        }
        VBox.setVgrow(body, Priority.ALWAYS);

        VBox card = new VBox(header, body);
        card.getStyleClass().add("modal-card");
        card.setMinWidth(420);
        card.setPrefWidth(560);
        card.setMaxWidth(560);
        card.setMaxHeight(Region.USE_PREF_SIZE);

        Region backdrop = new Region();
        backdrop.getStyleClass().add("modal-backdrop");
        backdrop.setPickOnBounds(true);

        StackPane overlay = new StackPane(backdrop, card);
        overlay.getStyleClass().add("modal-overlay");
        // a click outside the card closes the modal, like Escape and the close button; JavaFX
        // only delivers the click when press and release both land on the backdrop, so dragging
        // a text selection out of the card and letting go outside it leaves the modal open
        backdrop.addEventHandler(MouseEvent.ANY, mouseEvent -> {
            if (mouseEvent.getEventType() == MouseEvent.MOUSE_CLICKED
                    && mouseEvent.getButton() == MouseButton.PRIMARY) {
                appShell.getChildren().remove(overlay);
            }
            // nothing behind the modal reacts while it is open
            mouseEvent.consume();
        });
        closeButton.setOnAction(event -> appShell.getChildren().remove(overlay));
        appShell.getChildren().add(overlay);
    }

    /** Brings the main window back from the tray, restored and in the foreground. */
    private void showMainWindow() {
        if (mainStage == null || shuttingDown) {
            return;
        }
        mainStage.setIconified(false);
        if (!mainStage.isShowing()) {
            mainStage.show();
        }
        // A tray click belongs to the shell, so Windows will not hand this process the foreground
        // on its own; going on top briefly raises the window without pinning it there.
        mainStage.setAlwaysOnTop(true);
        mainStage.toFront();
        mainStage.requestFocus();
        PauseTransition unpin = new PauseTransition(Duration.millis(300));
        unpin.setOnFinished(event -> {
            if (mainStage.isShowing() && !mainStage.isIconified()) {
                mainStage.setAlwaysOnTop(false);
                mainStage.toFront();
                mainStage.requestFocus();
            }
        });
        unpin.play();
    }

    /**
     * Looks for a newer release. A check the user asked for reports whatever it finds, including
     * that there is nothing; the one on startup only speaks up when there is an update, and stays
     * quiet about a release the user chose to skip.
     */
    private void checkForUpdates(boolean requestedByUser) {
        String current = appVersion();
        if (!UpdateChecker.isComparable(current)) {
            if (requestedByUser) {
                setStatus(Messages.get("status.update.dev_build"));
            }
            return;
        }
        if (requestedByUser) {
            setStatus(Messages.get("status.update.checking"));
        }
        try {
            UpdateChecker.Release release = UpdateChecker.fetchLatest();
            if (release == null || !UpdateChecker.isNewer(release.version, current)) {
                if (requestedByUser) {
                    setStatus(Messages.format("status.update.latest", current));
                }
                return;
            }
            if (!requestedByUser
                    && release.version.equals(DesktopPreferences.getSkippedUpdateVersion())) {
                return;
            }
            Platform.runLater(() -> showUpdateModal(release, current));
        } catch (Exception e) {
            if (requestedByUser) {
                setStatus(Messages.format("status.update.check_failed", e.getMessage()));
            }
        }
    }

    /**
     * Shows a page in a shownotes view. A link clicked in it opens in the browser and the page is
     * put back: the view is too small to browse in and has no way back.
     */
    private void showHtml(WebView view, String page) {
        view.getProperties().put(SHOWN_PAGE, page);
        if (view.getProperties().putIfAbsent(LINKS_TO_BROWSER, Boolean.TRUE) == null) {
            view.getEngine().locationProperty().addListener((obs, oldLocation, newLocation) -> {
                if (newLocation != null
                        && (newLocation.startsWith("http://") || newLocation.startsWith("https://"))) {
                    getHostServices().showDocument(newLocation);
                    String shown = (String) view.getProperties().get(SHOWN_PAGE);
                    Platform.runLater(() -> view.getEngine().loadContent(shown));
                }
            });
        }
        view.getEngine().loadContent(page);
    }

    private void showUpdateModal(UpdateChecker.Release release, String current) {
        Label heading = new Label(Messages.format("update.heading", release.version));
        heading.setStyle("-fx-font-size: 15px; -fx-font-weight: bold;");
        Label installed = new Label(Messages.format("update.installed", current));
        installed.getStyleClass().add("muted-label");

        WebView notes = new WebView();
        notes.setPrefHeight(240);
        showHtml(notes, Shownotes.toPage(null, releaseNotesHtml(release.notes), ThemeManager.isDark()));

        Label status = new Label();
        status.setWrapText(true);
        ProgressBar progress = new ProgressBar(0);
        progress.setMaxWidth(Double.MAX_VALUE);
        progress.setVisible(false);
        progress.setManaged(false);

        Button install = new Button(release.hasInstaller() ? Messages.get("update.install")
                : Messages.get("update.open_release_page"));
        install.setDefaultButton(true);
        Button later = new Button(Messages.get("update.later"));
        Button skip = new Button(Messages.get("update.skip"));
        HBox buttons = new HBox(8, install, later, skip);

        VBox pane = new VBox(12, heading, installed, notes, progress, status, buttons);
        pane.setPadding(new Insets(8));
        VBox.setVgrow(notes, Priority.ALWAYS);
        showModal(Messages.get("update.title"), pane);

        later.setOnAction(event -> closeTopModal());
        skip.setOnAction(event -> {
            DesktopPreferences.setSkippedUpdateVersion(release.version);
            closeTopModal();
            setStatus(Messages.format("status.update.skipped", release.version));
        });
        install.setOnAction(event -> {
            if (!release.hasInstaller()) {
                getHostServices().showDocument(release.pageUrl);
                closeTopModal();
                return;
            }
            install.setDisable(true);
            later.setDisable(true);
            skip.setDisable(true);
            progress.setVisible(true);
            progress.setManaged(true);
            status.setText(Messages.format("update.downloading", release.installerName));
            downloadAndInstall(release, progress, status, install, later, skip);
        });
    }

    private void downloadAndInstall(UpdateChecker.Release release, ProgressBar progress,
            Label status, Button install, Button later, Button skip) {
        background.submit(() -> {
            try {
                File installer = UpdateDownloader.download(release, (read, total) -> {
                    double fraction = total > 0 ? read / (double) total : -1;
                    Platform.runLater(() -> progress.setProgress(fraction));
                });
                Platform.runLater(() -> {
                    status.setText(Messages.get("update.starting_installer"));
                    launchInstaller(installer);
                });
            } catch (Exception e) {
                android.util.Log.e("DesktopApp", "Update download failed", e);
                String friendly = friendlyUpdateError(e);
                Platform.runLater(() -> {
                    progress.setVisible(false);
                    progress.setManaged(false);
                    status.setText(Messages.format("update.failed", friendly));
                    install.setDisable(false);
                    later.setDisable(false);
                    skip.setDisable(false);
                    install.setText(Messages.get("update.retry"));
                    install.setDefaultButton(true);
                    install.setOnAction(retry -> {
                        install.setDisable(true);
                        later.setDisable(true);
                        skip.setDisable(true);
                        progress.setVisible(true);
                        progress.setManaged(true);
                        progress.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
                        status.setText(Messages.format("update.downloading", release.installerName));
                        downloadAndInstall(release, progress, status, install, later, skip);
                    });
                    ensureOpenReleasePageButton(release, install);
                });
            }
        });
    }

    /**
     * Adds an "Open release page" button next to Retry after a failed download, so a repeated
     * failure still leaves a way out. Added once; later failures reuse it.
     */
    private void ensureOpenReleasePageButton(UpdateChecker.Release release, Button install) {
        if (!(install.getParent() instanceof HBox buttons)) {
            return;
        }
        for (Node child : buttons.getChildren()) {
            if ("openReleasePage".equals(child.getUserData())) {
                return;
            }
        }
        Button page = new Button(Messages.get("update.open_release_page"));
        page.setUserData("openReleasePage");
        page.setOnAction(open -> {
            getHostServices().showDocument(release.pageUrl);
            closeTopModal();
        });
        buttons.getChildren().add(1, page);
    }

    /**
     * What the update dialog shows for a download failure. A bare file-system path (what a
     * locked {@code .part} file used to surface as) tells the user nothing, so it becomes
     * actionable text; anything else passes through.
     */
    static String friendlyUpdateError(Exception e) {
        String message = e == null ? null : e.getMessage();
        if (message == null || message.isBlank() || looksLikeUpdatePath(message)) {
            return Messages.get("update.error.save_failed");
        }
        return message.endsWith(".") ? message.substring(0, message.length() - 1) + "." : message;
    }

    private static boolean looksLikeUpdatePath(String message) {
        String trimmed = message.trim();
        return trimmed.endsWith(".part")
                || trimmed.matches("(?i)^[a-z]:\\\\.*")
                || trimmed.matches("^/[^\\n]*");
    }

    /**
     * Hands over to the installer and quits. The installer replaces the files this app is running
     * from, so it cannot do its job while the app is still holding them.
     */
    private void launchInstaller(File installer) {
        try {
            new ProcessBuilder(installer.getAbsolutePath())
                    .directory(installer.getParentFile())
                    .start();
        } catch (Exception e) {
            setStatus(Messages.format("status.update.installer_failed", e.getMessage()));
            return;
        }
        trayManager.remove();
        trayActive = false;
        shutdown();
    }

    /** GitHub release bodies are Markdown; only the bits the notes actually use are converted. */
    static String releaseNotesHtml(String notes) {
        if (notes == null || notes.isBlank()) {
            return "<p><i>" + Messages.get("update.no_notes") + "</i></p>";
        }
        StringBuilder html = new StringBuilder();
        boolean inList = false;
        for (String rawLine : notes.replace("\r\n", "\n").split("\n")) {
            String line = rawLine.trim();
            if (line.isEmpty()) {
                continue;
            }
            boolean bullet = line.startsWith("- ") || line.startsWith("* ");
            if (bullet && !inList) {
                html.append("<ul>");
                inList = true;
            } else if (!bullet && inList) {
                html.append("</ul>");
                inList = false;
            }
            String text = escapeHtml(bullet ? line.substring(2).trim() : line);
            // **bold** is the only inline mark the project's own notes use
            text = text.replaceAll("\\*\\*(.+?)\\*\\*", "<b>$1</b>");
            html.append(bullet ? "<li>" + text + "</li>" : "<p>" + text + "</p>");
        }
        if (inList) {
            html.append("</ul>");
        }
        return html.toString();
    }

    private static String escapeHtml(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private void openProjectPage() {
        try {
            getHostServices().showDocument(PROJECT_URL);
            setStatus(Messages.format("status.opened", PROJECT_URL));
        } catch (Exception e) {
            setStatus(Messages.format("status.browser_failed", e.getMessage()));
        }
    }

    /** Whether this is a run from source, with no released version to compare against. */
    static boolean isDevBuild() {
        return !UpdateChecker.isComparable(appVersion());
    }

    /**
     * Dev-only: restarts the app from source through the Gradle wrapper, so the running app is
     * the latest build — the wrapper rebuilds whatever changed before it launches. Runs the new
     * copy in its own window first, then gets out of the way like handing over to the installer.
     */
    private void reloadAll() {
        File root = projectDir(new File(System.getProperty("user.dir", ".")));
        if (root == null) {
            setStatus(Messages.format("status.dev.not_checkout", System.getProperty("user.dir", ".")));
            return;
        }
        try {
            new ProcessBuilder("cmd.exe", "/c", "start", "AntennaPod Desktop",
                    "gradlew.bat", ":app:run")
                    .directory(root)
                    .start();
        } catch (Exception e) {
            setStatus(Messages.format("status.dev.reload_failed", e.getMessage()));
            return;
        }
        trayManager.remove();
        trayActive = false;
        shutdown();
    }

    /** Dev-only: opens the project folder (the working directory when run from source). */
    private void openProjectDir() {
        File dir = projectDir(new File(System.getProperty("user.dir", ".")));
        if (dir == null) {
            setStatus(Messages.format("status.dev.not_checkout", System.getProperty("user.dir", ".")));
            return;
        }
        try {
            getHostServices().showDocument(dir.toURI().toString());
            setStatus(Messages.format("status.opened", dir));
        } catch (Exception e) {
            setStatus(Messages.format("status.dev.open_folder_failed", e.getMessage()));
        }
    }

    /**
     * The checkout {@code workingDir} sits in, or null when it is not one. Gradle runs the app
     * with the working directory set to the {@code app} module, so the parents are walked up
     * until the wrapper or the settings file shows up. Kept separate so it can be tested
     * without starting the UI.
     */
    static File projectDir(File workingDir) {
        try {
            File dir = workingDir.getCanonicalFile();
            while (dir != null) {
                if (new File(dir, "settings.gradle").isFile()
                        || new File(dir, "gradlew.bat").isFile()) {
                    return dir;
                }
                dir = dir.getParentFile();
            }
        } catch (Exception ignored) {
            // not a usable directory
        }
        return null;
    }

    private static final String APP_NAME = "AntennaPod Desktop";

    private static String appVersion() {
        Package appPackage = DesktopApp.class.getPackage();
        String version = appPackage == null ? null : appPackage.getImplementationVersion();
        return version == null || version.isEmpty() ? "dev" : version;
    }

    private List<Image> appIcons() {
        List<Image> icons = new ArrayList<>();
        for (String name : new String[]{"/icons/app-icon-16.png", "/icons/app-icon-32.png",
                "/icons/app-icon-48.png", "/icons/app-icon-64.png", "/icons/app-icon-128.png",
                "/icons/app-icon-256.png"}) {
            java.io.InputStream stream = getClass().getResourceAsStream(name);
            if (stream != null) {
                try {
                    icons.add(new Image(stream));
                } catch (Exception e) {
                    // ignore a broken icon resource
                } finally {
                    try {
                        stream.close();
                    } catch (Exception e) {
                        // ignore
                    }
                }
            }
        }
        return icons;
    }

    /** Set on the instance {@link #restartApp} starts, which waits for its predecessor to exit. */
    private static final String RESTART_ENV = "ANTENNAPOD_DESKTOP_RESTART";

    private boolean acquireInstanceLock() {
        try {
            java.nio.channels.FileChannel channel = java.nio.channels.FileChannel.open(
                    new File(DesktopPreferences.getDataDir(), "antennapod.lock").toPath(),
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.WRITE);
            java.nio.channels.FileLock lock = channel.tryLock();
            // a restart starts us while the old instance is still shutting down: give it a moment
            for (int wait = 0; lock == null && System.getenv(RESTART_ENV) != null && wait < 60; wait++) {
                Thread.sleep(250);
                lock = channel.tryLock();
            }
            if (lock == null) {
                channel.close();
                return false;
            }
            instanceLockChannel = channel;
            instanceLock = lock;
            return true;
        } catch (Exception e) {
            return true;
        }
    }

    private boolean hasModal() {
        return appShell != null && appShell.getChildren().stream()
                .anyMatch(node -> node.getStyleClass().contains("modal-overlay"));
    }

    private void closeTopModal() {
        for (int i = appShell.getChildren().size() - 1; i >= 0; i--) {
            Node node = appShell.getChildren().get(i);
            if (node.getStyleClass().contains("modal-overlay")) {
                appShell.getChildren().remove(i);
                return;
            }
        }
    }

    private void hideSidebar() {
        if (!sidebar.isVisible() && !sidebar.isManaged()) {
            return;
        }
        sidebarContent.getChildren().clear();
        preserveFeedWidthBeforeSidebarChange();
        sidebar.setVisible(false);
        sidebar.setManaged(false);
        ThemeManager.applySavedMode();
    }

    private class FeedCell extends ListCell<Feed> {
        private final ImageView art = new ImageView();
        private final Label titleLabel = new Label();
        private final Label countLabel = new Label();
        private final Label newCountBadge = new Label();
        private final Tooltip newCountTooltip = new Tooltip();
        private final HBox row;

        FeedCell() {
            setPrefWidth(0);
            art.setFitWidth(40);
            art.setFitHeight(40);
            titleLabel.setWrapText(true);
            titleLabel.setMinWidth(0);
            titleLabel.setMaxWidth(Double.MAX_VALUE);
            countLabel.getStyleClass().add("muted-label");
            countLabel.setMinWidth(0);
            countLabel.setMaxWidth(Double.MAX_VALUE);
            countLabel.setWrapText(false);
            newCountBadge.getStyleClass().add("badge-new");
            newCountBadge.setMinWidth(Region.USE_PREF_SIZE);
            newCountBadge.setTooltip(newCountTooltip);
            newCountBadge.setVisible(false);
            newCountBadge.setManaged(false);
            VBox texts = new VBox(2, titleLabel, countLabel);
            texts.setMinWidth(0);
            texts.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(texts, Priority.ALWAYS);
            row = new HBox(8, art, texts, newCountBadge);
            row.setAlignment(Pos.CENTER_LEFT);
            row.setMaxWidth(Double.MAX_VALUE);
        }

        @Override
        protected void updateItem(Feed feed, boolean empty) {
            super.updateItem(feed, empty);
            if (empty || feed == null) {
                setGraphic(null);
                setContextMenu(null);
                setAccessibleText(null);
                return;
            }
            titleLabel.setText(feed.getTitle() != null ? feed.getTitle() : feed.getDownloadUrl());
            int[] counts = feedCounts.get(feed.getId());
            int unplayed = counts != null ? counts[0] : 0;
            int newCount = counts != null ? counts[1] : 0;
            String unplayedText = unplayed > 0 ? Messages.format("feeds.count.unplayed", unplayed) : "";
            countLabel.setText(unplayedText);
            setAccessibleText(titleLabel.getText() + (unplayedText.isEmpty() ? "" : ", " + unplayedText)
                    + (counts != null && counts[1] > 0 ? ", " + Messages.format("feeds.count.new", counts[1]) : ""));
            boolean hasNew = newCount > 0;
            newCountBadge.setText(String.valueOf(newCount));
            newCountTooltip.setText(newCount == 1 ? Messages.get("count.new_episode.one")
                    : Messages.format("count.new_episode.other", newCount));
            newCountBadge.setVisible(hasNew);
            newCountBadge.setManaged(hasNew);
            updateArt(feed.getImageUrl());
            setGraphic(row);
            setContextMenu(buildFeedContextMenu(feed));
        }

        private void updateArt(String imageUrl) {
            if (imageUrl == null || imageUrl.isEmpty()) {
                art.setUserData(null);
                art.setImage(null);
                return;
            }
            if (imageUrl.equals(art.getUserData())) {
                return;
            }
            art.setUserData(imageUrl);
            art.setImage(ImageCache.get(imageUrl, 40, 40));
        }
    }

    private VBox buildEpisodePane() {
        feedTitleLabel = new Label(Messages.get("episodes.select_podcast"));
        feedTitleLabel.setStyle("-fx-font-size: 16px; -fx-font-weight: bold;");
        sortBox = new ComboBox<>();
        sortBox.getItems().addAll(Messages.get("sort.newest"), Messages.get("sort.oldest"),
                Messages.get("sort.shortest"),
                Messages.get("sort.longest"), Messages.get("sort.title"));
        sortBox.setValue(Messages.get("sort.newest"));
        sortBox.setOnAction(event -> {
            if (selectedFeed != null && !sortBoxProgrammatic) {
                saveSortCode(selectedFeed, sortCode(sortBox.getValue()));
            }
        });
        Button playAllButton = new Button(Messages.get("episodes.play_all"), Icons.play());
        playAllButton.setOnAction(event -> playAll());
        episodeFilterField = new TextField();
        episodeFilterField.setPromptText(Messages.get("episodes.search.prompt"));
        episodeFilterField.setPrefWidth(160);
        episodeFilterField.textProperty().addListener((obs, oldText, newText) -> applyEpisodeFilter());
        episodeStateBox = new ComboBox<>(FXCollections.observableArrayList(EpisodeFilter.values()));
        episodeStateBox.setValue(DesktopPreferences.getEpisodeFilter());
        episodeStateBox.setTooltip(new Tooltip(Messages.get("episodes.filter.tooltip")));
        episodeStateBox.setOnAction(event -> {
            EpisodeFilter chosen = episodeStateBox.getValue();
            prefsWriter.submit(() -> DesktopPreferences.setEpisodeFilter(chosen));
            applyEpisodeFilter();
        });
        Button refreshButton = iconButton(Icons.refresh(), Messages.get("episodes.refresh.tooltip"));
        episodeRefreshButton = refreshButton;
        refreshButton.setOnAction(event -> refreshOpenFeed());
        playAllButton.setTooltip(new Tooltip(Messages.get("episodes.play_all.tooltip")));
        // title on the left, the list's controls pushed to the right edge
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox controls = new HBox(8, episodeFilterField, episodeStateBox, sortBox, refreshButton,
                playAllButton);
        controls.setAlignment(Pos.CENTER_LEFT);
        controls.setMinWidth(0);
        HBox header = new HBox(8, feedTitleLabel, spacer, controls);
        header.setAlignment(Pos.CENTER_LEFT);
        refreshButton.setMinWidth(Region.USE_PREF_SIZE);
        feedTitleLabel.setMinWidth(60);
        episodeList = new ListView<>(visibleEpisodes);
        episodeList.setPlaceholder(new Label(Messages.get("episodes.empty")));
        episodeList.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        episodeList.setCellFactory(list -> new EpisodeCell());
        episodeList.setOnMouseClicked(event -> {
            if (event.getClickCount() == 2) {
                FeedItem selected = episodeList.getSelectionModel().getSelectedItem();
                if (selected != null && selected.getMedia() != null) {
                    playback.play(selected, playbackOrder());
                    setStatus(Messages.format("status.playing", selected.getTitle()));
                }
            }
        });
        VBox pane = new VBox(6, header, episodeList);
        pane.setPadding(new Insets(8));
        VBox.setVgrow(episodeList, Priority.ALWAYS);
        pane.widthProperty().addListener((obs, oldWidth, width) ->
                layoutEpisodeHeader(pane, header, controls, width.doubleValue()));
        layoutEpisodeHeader(pane, header, controls, pane.getWidth());
        return pane;
    }

    /** Room the podcast title keeps next to the controls before they drop to their own row. */
    private static final double EPISODE_HEADER_TITLE_ROOM = 140;
    /** What the one-row controls measured last, reused while they sit on their own row. */
    private double episodeHeaderControlsWidth;

    /**
     * Keeps the episode header readable as the pane narrows (the sidebar opening takes a third of
     * the window): wide, the controls sit right of the title with whole labels; narrow, they drop
     * to a row of their own under the title, the search field takes the slack, and the pickers
     * and Play all may shrink rather than run off the edge.
     */
    private void layoutEpisodeHeader(VBox pane, HBox header, HBox controls, double width) {
        boolean inHeader = header.getChildren().contains(controls);
        if (inHeader) {
            episodeHeaderControlsWidth = controls.prefWidth(-1);
        }
        Insets padding = pane.getPadding();
        double needed = episodeHeaderControlsWidth + EPISODE_HEADER_TITLE_ROOM
                + header.getSpacing() * 2 + padding.getLeft() + padding.getRight();
        boolean oneRow = width <= 0 || episodeHeaderControlsWidth <= 0 || width >= needed;
        if (oneRow && !inHeader) {
            pane.getChildren().remove(controls);
            header.getChildren().add(controls);
        } else if (!oneRow && inHeader) {
            header.getChildren().remove(controls);
            pane.getChildren().add(1, controls);
        }
        HBox.setHgrow(episodeFilterField, oneRow ? Priority.NEVER : Priority.ALWAYS);
        episodeFilterField.setMinWidth(oneRow ? 90 : 60);
        double pickerMin = oneRow ? Region.USE_PREF_SIZE : 70;
        episodeStateBox.setMinWidth(pickerMin);
        sortBox.setMinWidth(pickerMin);
        Button playAll = (Button) controls.getChildren().get(controls.getChildren().size() - 1);
        playAll.setContentDisplay(oneRow ? javafx.scene.control.ContentDisplay.LEFT
                : javafx.scene.control.ContentDisplay.GRAPHIC_ONLY);
        playAll.setMinWidth(Region.USE_PREF_SIZE);
    }

    private void saveSortCode(Feed feed, String code) {
        prefsWriter.submit(() -> {
            try {
                FeedPrefs prefs = database.getFeedPrefs(feed.getId());
                prefs.sortCode = code;
                database.saveFeedPrefs(prefs);
                loadEpisodes(feed);
            } catch (Exception e) {
                setStatus(Messages.format("status.sort.save_failed", e.getMessage()));
            }
        });
    }

    private void playAll() {
        List<FeedItem> order = playbackOrder();
        for (FeedItem item : order) {
            if (item.getMedia() != null) {
                playback.play(item, order);
                setStatus(Messages.format("status.playing_from", item.getTitle()));
                return;
            }
        }
        setStatus(Messages.get("status.nothing_playable.list"));
    }

    private List<FeedItem> playbackOrder() {
        List<FeedItem> order = new ArrayList<>(visibleEpisodes);
        EpisodeSorter.sort(order, EpisodeSorter.OLDEST);
        return order;
    }

    /**
     * Double-clicking a subscription resumes its latest in-progress episode, or starts the
     * top playable one in its own order when nothing is in progress.
     */
    private void playFeed(Feed feed) {
        setStatus(Messages.format("status.feed.starting", feed.getTitle()));
        background.submit(() -> {
            try {
                Feed full = database.getFeed(feed.getId());
                FeedPrefs prefs = database.getFeedPrefs(feed.getId());
                List<FeedItem> items = full.getItems() != null
                        ? new ArrayList<>(full.getItems()) : new ArrayList<>();
                EpisodeSorter.sort(items, prefs.sortCode);
                FeedItem start = latestInProgress(items);
                if (start == null) {
                    for (FeedItem item : items) {
                        if (item.getMedia() != null) {
                            start = item;
                            break;
                        }
                    }
                }
                if (start == null) {
                    setStatus(Messages.get("status.nothing_playable.feed"));
                    return;
                }
                FeedItem first = start;
                Platform.runLater(() -> playback.play(first, items));
                setStatus(Messages.format("status.playing", first.getTitle()));
            } catch (Exception e) {
                setStatus(Messages.format("status.feed.start_failed", e.getMessage()));
            }
        });
    }

    /** The most recently played unfinished episode, or null when none is in progress. */
    static FeedItem latestInProgress(List<FeedItem> items) {
        FeedItem latest = null;
        long latestTime = -1;
        for (FeedItem item : items) {
            if (item == null || item.getMedia() == null || item.isPlayed()) {
                continue;
            }
            int position = item.getMedia().getPosition();
            int duration = item.getMedia().getDuration();
            if (position <= 0 || (duration > 0 && position >= duration)) {
                continue;
            }
            long playedAt = item.getMedia().getLastPlayedTimeStatistics();
            if (latest == null || playedAt > latestTime) {
                latest = item;
                latestTime = playedAt;
            }
        }
        return latest;
    }

    private void applyEpisodeFilter() {
        String query = episodeFilterField.getText().trim().toLowerCase(Locale.ROOT);
        EpisodeFilter state = episodeStateBox.getValue() != null
                ? episodeStateBox.getValue() : EpisodeFilter.ALL;
        visibleEpisodes.setPredicate(item -> state.matches(item) && (query.isEmpty()
                || (item.getTitle() != null && item.getTitle().toLowerCase(Locale.ROOT).contains(query))));
    }

    /**
     * Redraws the episode rows after an action changed what the state filter looks at (played,
     * favorite, downloaded): the filtered view only re-tests rows when its predicate is set
     * again, so without this a row marked played would linger under "Unplayed".
     */
    private void refilterEpisodes() {
        if (episodeStateBox.getValue() != null && episodeStateBox.getValue() != EpisodeFilter.ALL) {
            applyEpisodeFilter();
        }
        episodeList.refresh();
    }

    private void showSleepTimerMenu() {
        javafx.scene.control.ContextMenu menu = new javafx.scene.control.ContextMenu();
        javafx.scene.control.MenuItem off = new javafx.scene.control.MenuItem(Messages.get("sleep.off"));
        off.setOnAction(event -> setSleepTimer(SleepTimer.Mode.OFF, 0));
        menu.getItems().add(off);
        for (long minutes : new long[]{5, 10, 15, 30, 45, 60}) {
            javafx.scene.control.MenuItem item =
                    new javafx.scene.control.MenuItem(Messages.format("sleep.minutes", minutes));
            item.setOnAction(event -> setSleepTimer(SleepTimer.Mode.AFTER_MINUTES, minutes));
            menu.getItems().add(item);
        }
        javafx.scene.control.MenuItem endOfEpisode =
                new javafx.scene.control.MenuItem(Messages.get("sleep.end_of_episode"));
        endOfEpisode.setOnAction(event -> setSleepTimer(SleepTimer.Mode.END_OF_EPISODE, 0));
        menu.getItems().add(endOfEpisode);
        menu.show(sleepButton, javafx.geometry.Side.TOP, 0, 0);
    }

    private void setSleepTimer(SleepTimer.Mode mode, long minutes) {
        if (mode == SleepTimer.Mode.AFTER_MINUTES) {
            sleepTimer.startMinutes(minutes);
            setStatus(Messages.format("status.sleep.minutes", minutes));
        } else if (mode == SleepTimer.Mode.END_OF_EPISODE) {
            sleepTimer.startEndOfEpisode();
            playback.setStopAfterCurrent(true);
            setStatus(Messages.get("status.sleep.end_of_episode"));
        } else {
            sleepTimer.cancel();
            playback.setStopAfterCurrent(false);
            setStatus(Messages.get("status.sleep.off"));
        }
        updateSleepButton();
    }

    private void updateSleepButton() {
        SleepTimer.Mode mode = sleepTimer.getMode();
        if (mode == SleepTimer.Mode.AFTER_MINUTES) {
            sleepButton.setText(formatDuration(sleepTimer.getRemainingMs()));
        } else if (mode == SleepTimer.Mode.END_OF_EPISODE) {
            sleepButton.setText(Messages.get("sleep.button.end_of_episode"));
        } else {
            sleepButton.setText("");
        }
    }

    private static Button iconButton(javafx.scene.Node graphic, String tooltip) {
        Button button = new Button("", graphic);
        button.getStyleClass().add("icon-button");
        button.setTooltip(new Tooltip(tooltip));
        nameFromTooltip(button);
        return button;
    }

    /**
     * Gives a control with no text of its own (an icon button) its tooltip as the name a screen
     * reader announces, kept in step when the tooltip or its text changes later.
     */
    static void nameFromTooltip(javafx.scene.control.Control control) {
        javafx.beans.value.ChangeListener<String> onText =
                (obs, oldText, text) -> control.setAccessibleText(text);
        control.tooltipProperty().addListener((obs, oldTip, tip) -> {
            if (oldTip != null) {
                oldTip.textProperty().removeListener(onText);
            }
            if (tip != null) {
                tip.textProperty().addListener(onText);
            }
            control.setAccessibleText(tip != null ? tip.getText() : null);
        });
        Tooltip current = control.getTooltip();
        if (current != null) {
            current.textProperty().addListener(onText);
            control.setAccessibleText(current.getText());
        }
    }

    /** Names the inputs a screen reader would otherwise announce only by kind ("combo box"). */
    private void nameInputs() {
        addField.setAccessibleText(Messages.get("a11y.add_field"));
        feedFilterField.setAccessibleText(Messages.get("a11y.feed_filter"));
        tagBox.setAccessibleText(Messages.get("a11y.tag_filter"));
        episodeFilterField.setAccessibleText(Messages.get("a11y.episode_filter"));
        episodeStateBox.setAccessibleText(Messages.get("a11y.episode_state"));
        sortBox.setAccessibleText(Messages.get("a11y.episode_sort"));
        feedList.setAccessibleText(Messages.get("a11y.feed_list"));
        episodeList.setAccessibleText(Messages.get("a11y.episode_list"));
        speedBox.setAccessibleText(Messages.get("a11y.speed"));
        volumeSlider.setAccessibleText(Messages.get("a11y.volume"));
        seekSlider.setAccessibleText(Messages.get("a11y.seek"));
    }

    private VBox buildPlayerBar() {
        Button prevButton = iconButton(Icons.previous(), Messages.get("player.previous"));
        prevButton.setOnAction(event -> playback.playPrevious());
        skipBackButton = iconButton(Icons.replay10(), "");
        skipBackButton.setOnAction(event ->
                skipBy(-DesktopPreferences.getSkipBackSec() * 1000));
        playPauseButton = iconButton(Icons.accent(Icons.play(26)), Messages.get("player.play_pause"));
        playPauseButton.setOnAction(event -> togglePlayPause());
        skipForwardButton = iconButton(Icons.forward30(), "");
        skipForwardButton.setOnAction(event ->
                skipBy(DesktopPreferences.getSkipForwardSec() * 1000));
        Button nextButton = iconButton(Icons.next(), Messages.get("player.next"));
        nextButton.setOnAction(event -> playback.playNext());
        Button stopButton = iconButton(Icons.stop(), Messages.get("player.stop"));
        stopButton.setOnAction(event -> playback.stop());
        updateSkipTooltips();

        loadingSpinner = new ProgressIndicator();
        loadingSpinner.setPrefSize(22, 22);
        loadingSpinner.setMaxSize(22, 22);
        loadingSpinner.setVisible(false);
        StackPane playPauseHolder = new StackPane(playPauseButton, loadingSpinner);

        nowPlayingArt = new ImageView();
        nowPlayingArt.setFitWidth(96);
        nowPlayingArt.setFitHeight(96);
        nowPlayingArt.setVisible(false);

        artPlaceholder = new StackPane(Icons.wave(34));
        artPlaceholder.getStyleClass().add("art-placeholder");
        artPlaceholder.setMinSize(96, 96);
        artPlaceholder.setPrefSize(96, 96);
        artPlaceholder.setMaxSize(96, 96);

        StackPane artBox = new StackPane(nowPlayingArt, artPlaceholder);
        artBox.setMinSize(96, 96);
        artBox.setPrefSize(96, 96);
        artBox.setMaxSize(96, 96);

        nowPlayingLabel = new Label(Messages.get("player.nothing_playing"));
        nowPlayingLabel.setMaxWidth(Double.MAX_VALUE);
        nowPlayingLabel.setStyle("-fx-cursor: hand;");
        nowPlayingLabel.setOnMouseClicked(event -> {
            FeedMedia current = playback.getCurrentMedia();
            if (current != null && current.getItem() != null) {
                showEpisodeDetails(current.getItem());
            }
        });
        HBox.setHgrow(nowPlayingLabel, Priority.ALWAYS);

        elapsedLabel = buildTimeLabel(Pos.CENTER_RIGHT);
        totalLabel = buildTimeLabel(Pos.CENTER_LEFT);
        Tooltip timeTooltip = new Tooltip(Messages.get("player.time.tooltip"));
        elapsedLabel.setTooltip(timeTooltip);
        totalLabel.setTooltip(timeTooltip);
        javafx.event.EventHandler<MouseEvent> timeToggle = event -> {
            showRemainingTime = !showRemainingTime;
            updateTimeLabels(playback.getPosition(), playback.getDuration());
        };
        elapsedLabel.setOnMouseClicked(timeToggle);
        totalLabel.setOnMouseClicked(timeToggle);

        seekSlider = new Slider(0, 1000, 0);
        seekSlider.setPrefWidth(320);
        seekSlider.setDisable(true);
        seekSlider.setOnMousePressed(event -> sliderDragging = true);
        seekSlider.setOnMouseReleased(event -> {
            if (sliderDragging) {
                sliderDragging = false;
                playback.seek((int) seekSlider.getValue());
            }
        });
        seekSlider.valueProperty().addListener((obs, oldValue, newValue) -> {
            if (sliderDragging) {
                updateTimeLabels(newValue.intValue(), (int) seekSlider.getMax());
                paintSeekTrack(newValue.intValue(), lastBufferedMs, (int) seekSlider.getMax());
            }
        });
        ghostMarker = new StackPane();
        ghostMarker.getStyleClass().add("ghost-marker");
        // a hollow diamond, never a bar or a dot, so it cannot read as progress; pickable
        // so the tooltip below has something to hover, and small enough that the seek
        // slider underneath keeps all but that sliver
        ghostMarker.setMouseTransparent(false);
        Tooltip.install(ghostMarker, new Tooltip(SYNCED_TIP));
        ghostMarker.setVisible(false);
        ghostMarker.setPrefSize(SYNCED_MARKER_SIZE, SYNCED_MARKER_SIZE);
        ghostMarker.setMinSize(SYNCED_MARKER_SIZE, SYNCED_MARKER_SIZE);
        ghostMarker.setMaxSize(SYNCED_MARKER_SIZE, SYNCED_MARKER_SIZE);
        ghostMarker.setRotate(45);
        StackPane.setAlignment(ghostMarker, Pos.CENTER_LEFT);
        seekPulse = buildLoadingPulse(72, 5, seekSlider.widthProperty());
        seekPulse.setVisible(false);
        StackPane stack = new StackPane(seekSlider, ghostMarker, seekPulse);
        StackPane.setAlignment(stack, Pos.CENTER_LEFT);

        speedBox = new ComboBox<>();
        speedBox.getItems().addAll(SPEED_OPTIONS);
        speedBox.setValue(closestSpeed(DesktopPreferences.getPlaybackSpeed()));
        speedBox.setOnAction(event -> {
            String value = speedBox.getValue().replace("x", "");
            playback.setRate(Float.parseFloat(value));
        });

        silenceButton = iconButton(Icons.wave(), Messages.get("player.skip_silence"));
        silenceButton.setOnAction(event ->
                setSilenceSkipping(!DesktopPreferences.getSkipSilence()));
        updateSilenceButtonTooltip();

        volumeSlider = new Slider(0, 1, DesktopPreferences.getDefaultVolume());
        volumeSlider.setPrefWidth(100);
        lastVolume = DesktopPreferences.getDefaultVolume() > 0
                ? DesktopPreferences.getDefaultVolume() : 1.0;
        volumeSlider.valueProperty().addListener((obs, oldValue, newValue) -> {
            double volume = newValue.doubleValue();
            playback.setVolume(volume);
            DesktopPreferences.setDefaultVolume(volume);
            paintVolumeTrack();
            if (volume > 0) {
                lastVolume = volume;
            }
            if (muteButton != null) {
                muteButton.setGraphic(volume <= 0 ? Icons.volumeOff() : Icons.volumeUp());
            }
        });
        muteButton = iconButton(volumeSlider.getValue() <= 0 ? Icons.volumeOff() : Icons.volumeUp(),
                Messages.get("player.mute"));
        muteButton.setOnAction(event -> toggleMute());

        sleepButton = new Button("", Icons.clock());
        sleepButton.setTooltip(new Tooltip(Messages.get("player.sleep_timer")));
        nameFromTooltip(sleepButton);
        sleepButton.setOnAction(event -> showSleepTimerMenu());

        chapterLabel = new Label("");
        chapterLabel.getStyleClass().add("muted-label");
        chapterLabel.setPrefWidth(160);
        chapterLabel.setMaxWidth(160);

        chapterPrevButton = iconButton(Icons.navigateBefore(), Messages.get("player.chapter.previous"));
        chapterPrevButton.setOnAction(event -> skipChapter(false));
        chapterNextButton = iconButton(Icons.navigateAfter(), Messages.get("player.chapter.next"));
        chapterNextButton.setOnAction(event -> skipChapter(true));
        setChapterButtonsVisible(false);

        HBox scrubRow = new HBox(8, elapsedLabel, stack, totalLabel, chapterLabel,
                chapterPrevButton, chapterNextButton);
        scrubRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(stack, Priority.ALWAYS);
        scrubRow.setOnScroll(event -> {
            if (Math.abs(event.getDeltaY()) >= 20 && playback.getCurrentMedia() != null) {
                skipBy(event.getDeltaY() > 0 ? 10000 : -10000);
                event.consume();
            }
        });

        HBox transportRow = new HBox(8, prevButton, skipBackButton, playPauseHolder,
                skipForwardButton, nextButton, stopButton);
        transportRow.setAlignment(Pos.CENTER);
        castButton = iconButton(Icons.cast(), Messages.get("cast.button"));
        castButton.setOnAction(event -> showCastMenu());
        HBox extrasRow = new HBox(8, speedBox, silenceButton, muteButton, volumeSlider, sleepButton,
                castButton);
        extrasRow.setAlignment(Pos.CENTER_RIGHT);
        // keep the overlay only as wide as its content, otherwise it swallows
        // the mouse clicks meant for the transport buttons underneath
        extrasRow.setMaxWidth(Region.USE_PREF_SIZE);

        nowPlayingLabel.setAlignment(Pos.CENTER_LEFT);
        HBox titleRow = new HBox(nowPlayingLabel);
        titleRow.setAlignment(Pos.CENTER_LEFT);
        // line the title up with the seek slider's left edge (elapsed label + spacing)
        titleRow.setPadding(new Insets(0, 0, 0, 60));

        // transport centred on the full bar width; extras overlaid on the right
        StackPane controlArea = new StackPane(transportRow, extrasRow);
        StackPane.setAlignment(transportRow, Pos.CENTER);
        StackPane.setAlignment(extrasRow, Pos.CENTER_RIGHT);

        statusLabel = new Label(Messages.get("status.ready"));
        statusLabel.setMinWidth(Region.USE_PREF_SIZE);
        statusLabel.setMaxWidth(220);
        statusLabel.setStyle("-fx-cursor: hand;");
        statusLabel.setOnMouseClicked(event -> {
            // the status line carries error detail worth pasting into a bug report
            copyText(statusLabel.getText());
        });

        VBox controlsColumn = new VBox(6, titleRow, scrubRow, controlArea);
        controlsColumn.setAlignment(Pos.BOTTOM_LEFT);
        controlsColumn.setMaxHeight(Region.USE_PREF_SIZE);
        HBox.setHgrow(controlsColumn, Priority.ALWAYS);
        artColumn = new VBox(4, artBox, statusLabel);
        artColumn.setAlignment(Pos.TOP_LEFT);
        artPlaceholder.setVisible(true);
        setArtColumnWidth(ART_COLUMN_WIDTH);

        HBox main = new HBox(12, artColumn, controlsColumn);
        main.setAlignment(Pos.CENTER_LEFT);
        main.setPadding(new Insets(8, 8, 6, 8));
        return new VBox(buildCastBar(), main);
    }

    /** "Casting to <TV>" with its own controls; shown only while an episode plays on a renderer. */
    private HBox buildCastBar() {
        castLabel = new Label();
        castLabel.setStyle("-fx-font-weight: bold;");
        castLabel.setMinWidth(0);
        HBox.setHgrow(castLabel, Priority.ALWAYS);
        castLabel.setMaxWidth(Double.MAX_VALUE);
        Button back = iconButton(Icons.replay10(), Messages.get("cast.back"));
        back.setOnAction(event -> skipBy(-DesktopPreferences.getSkipBackSec() * 1000));
        castPlayPauseButton = iconButton(Icons.pause(), Messages.get("cast.play_pause"));
        castPlayPauseButton.setOnAction(event -> togglePlayPause());
        Button forward = iconButton(Icons.forward30(), Messages.get("cast.forward"));
        forward.setOnAction(event -> skipBy(DesktopPreferences.getSkipForwardSec() * 1000));
        Button stop = new Button(Messages.get("cast.stop"), Icons.stop());
        stop.setOnAction(event -> stopCasting(false));
        HBox bar = new HBox(8, new javafx.scene.Group(Icons.cast()), castLabel, back, castPlayPauseButton,
                forward, stop);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(6, 8, 0, 8));
        bar.setVisible(false);
        bar.setManaged(false);
        castBar = bar;
        return bar;
    }

    // ---------------------------------------------------------------- casting (DLNA)

    /** Play/pause for the whole app: on the TV while casting, else the local player. */
    private void togglePlayPause() {
        CastSession session = castSession;
        if (session == null) {
            playback.togglePlayPause();
            return;
        }
        if (castPlaying) {
            session.pause();
        } else {
            session.resume();
        }
        castPlaying = !castPlaying;
        updateCastBar(session.lastPositionMs(), -1);
    }

    /** Skip for the whole app: on the TV while casting, else the local player. */
    private void skipBy(int deltaMs) {
        CastSession session = castSession;
        if (session == null) {
            playback.skip(deltaMs);
            return;
        }
        session.seek(Math.max(0, session.lastPositionMs() + deltaMs));
    }

    /** Looks for renderers on the network and lists them under the cast button. */
    private void showCastMenu() {
        javafx.scene.control.ContextMenu menu = new javafx.scene.control.ContextMenu();
        if (castSession != null) {
            javafx.scene.control.MenuItem stop = new javafx.scene.control.MenuItem(
                    Messages.format("cast.stop_to", castSession.renderer().name));
            stop.setOnAction(event -> stopCasting(false));
            menu.getItems().addAll(stop, new javafx.scene.control.SeparatorMenuItem());
        }
        javafx.scene.control.MenuItem searching =
                new javafx.scene.control.MenuItem(Messages.get("cast.searching"));
        searching.setDisable(true);
        menu.getItems().add(searching);
        menu.show(castButton, javafx.geometry.Side.TOP, 0, 0);
        background.submit(() -> {
            List<DlnaRenderer> found;
            try {
                found = DlnaDiscovery.discover(3000);
            } catch (Exception e) {
                found = new ArrayList<>();
                setStatus(Messages.format("status.cast.search_failed", e.getMessage()));
            }
            List<DlnaRenderer> renderers = found;
            Platform.runLater(() -> {
                menu.getItems().remove(searching);
                if (renderers.isEmpty()) {
                    javafx.scene.control.MenuItem none = new javafx.scene.control.MenuItem(
                            Messages.get("cast.none_found"));
                    none.setDisable(true);
                    menu.getItems().add(none);
                }
                for (DlnaRenderer renderer : renderers) {
                    javafx.scene.control.MenuItem item = new javafx.scene.control.MenuItem(renderer.name, Icons.cast());
                    item.setOnAction(event -> startCasting(renderer));
                    menu.getItems().add(item);
                }
                if (menu.isShowing()) {
                    // re-show so the menu grows to its new items
                    menu.hide();
                    menu.show(castButton, javafx.geometry.Side.TOP, 0, 0);
                }
            });
        });
    }

    /** Sends the loaded (or else the selected) episode to a renderer, from where it is. */
    private void startCasting(DlnaRenderer renderer) {
        FeedMedia current = playback.getCurrentMedia();
        FeedMedia media = current;
        int position;
        if (media != null) {
            position = playback.getPosition();
        } else {
            FeedItem selected = episodeList.getSelectionModel().getSelectedItem();
            media = selected != null ? selected.getMedia() : null;
            position = media != null ? media.getPosition() : 0;
        }
        if (media == null) {
            setStatus(Messages.get("status.cast.nothing"));
            return;
        }
        if (playback.isPlaying()) {
            playback.pause();
        }
        if (castSession != null) {
            stopCasting(false);
        }
        FeedMedia episode = media;
        int start = Math.max(0, position);
        try {
            if (castServer == null) {
                castServer = new CastServer();
            }
        } catch (java.io.IOException e) {
            setStatus(Messages.format("status.cast.start_failed", e.getMessage()));
            return;
        }
        CastSession session = new CastSession(renderer, castServer, new CastSession.Listener() {
            @Override
            public void onProgress(int positionMs, int durationMs, boolean playing) {
                Platform.runLater(() -> {
                    if (castSession == null) {
                        return;
                    }
                    castPlaying = playing;
                    updateCastBar(positionMs, durationMs);
                });
            }

            @Override
            public void onFinished() {
                Platform.runLater(() -> stopCasting(true));
            }

            @Override
            public void onError(String message) {
                setStatus(Messages.format("status.cast.error", renderer.name, message));
            }
        });
        castSession = session;
        castPlaying = true;
        session.cast(episode, start);
        String title = episode.getItem() != null ? episode.getItem().getTitle() : episode.getHumanReadableIdentifier();
        castBar.setVisible(true);
        castBar.setManaged(true);
        castLabel.setUserData(renderer.name + " · " + title);
        updateCastBar(start, episode.getDuration());
        setStatus(Messages.format("status.cast.started", title, renderer.name));
    }

    private void updateCastBar(int positionMs, int durationMs) {
        String what = castLabel.getUserData() != null ? castLabel.getUserData().toString() : "";
        String time = formatDuration(Math.max(positionMs, 0))
                + (durationMs > 0 ? " / " + formatDuration(durationMs) : "");
        castLabel.setText(Messages.format("cast.bar", what, time));
        castPlayPauseButton.setGraphic(castPlaying ? Icons.pause() : Icons.play());
    }

    /**
     * Ends casting: the TV stops, and where it got to becomes the episode's position (or the
     * episode is marked played when it finished there), so the PC carries on from there.
     */
    private void stopCasting(boolean finished) {
        CastSession session = castSession;
        if (session == null) {
            return;
        }
        castSession = null;
        castPlaying = false;
        session.close();
        castBar.setVisible(false);
        castBar.setManaged(false);
        FeedMedia media = session.media();
        String device = session.renderer().name;
        int reached = session.lastPositionMs();
        FeedMedia current = playback.getCurrentMedia();
        if (!finished && current != null && media != null && current.getId() == media.getId()) {
            // the local player still has it loaded: line it up with where the TV stopped
            playback.seek(reached);
        }
        if (media == null) {
            return;
        }
        if (finished && media.getItem() != null) {
            applyPlayedState(List.of(media.getItem()), true);
            setStatus(Messages.format("status.cast.finished", device));
            return;
        }
        background.submit(() -> {
            try {
                media.setPosition(reached);
                database.updatePlaybackState(media);
                setStatus(Messages.format("status.cast.stopped", device, formatDuration(reached)));
                Platform.runLater(this::refilterEpisodes);
            } catch (Exception e) {
                setStatus(Messages.format("status.cast.save_failed", e.getMessage()));
            }
        });
    }

    private static Label buildTimeLabel(Pos alignment) {
        Label label = new Label("0:00");
        label.setMinWidth(52);
        label.setAlignment(alignment);
        label.setStyle("-fx-cursor: hand;");
        return label;
    }

    private void resumeLastPlayed() {
        long mediaId = DesktopPreferences.getLastPlayedMediaId();
        if (mediaId < 0) {
            setStatus(Messages.get("status.resume.nothing"));
            return;
        }
        setStatus(Messages.get("status.resume.started"));
        background.submit(() -> {
            try {
                FeedMedia media = database.getMedia(mediaId);
                if (media == null) {
                    setStatus(Messages.get("status.resume.not_found"));
                    return;
                }
                FeedItem item = database.getItem(media.getItemId());
                if (item == null || item.getMedia() == null) {
                    setStatus(Messages.get("status.resume.not_found"));
                    return;
                }
                List<FeedItem> queue = new ArrayList<>();
                if (item.getFeedId() != 0) {
                    Feed feed = database.getFeed(item.getFeedId());
                    if (feed != null && feed.getItems() != null) {
                        queue.addAll(feed.getItems());
                        FeedPrefs prefs = database.getFeedPrefs(item.getFeedId());
                        EpisodeSorter.sort(queue, prefs.sortCode);
                    }
                }
                if (queue.isEmpty()) {
                    queue.add(item);
                }
                int position = item.getMedia().getPosition();
                Platform.runLater(() -> playback.playAt(item, queue, position));
            } catch (Exception e) {
                setStatus(Messages.format("status.resume.failed", e.getMessage()));
            }
        });
    }

    private void skipChapter(boolean forward) {
        FeedMedia current = playback.getCurrentMedia();
        if (current == null || current.getItem() == null) {
            return;
        }
        List<Chapter> chapters = current.getItem().getChapters();
        if (chapters == null || chapters.isEmpty()) {
            return;
        }
        int position = playback.getPosition();
        int index = Chapter.getAfterPosition(chapters, position);
        int target;
        if (forward) {
            target = index + 1 < chapters.size() ? (int) chapters.get(index + 1).getStart()
                    : playback.getDuration();
        } else if (index < 0) {
            target = 0;
        } else {
            long start = chapters.get(index).getStart();
            target = position > start + 2000 ? (int) start
                    : (index > 0 ? (int) chapters.get(index - 1).getStart() : 0);
        }
        playback.seek(target);
    }

    private void setChapterButtonsVisible(boolean visible) {
        if (chapterPrevButton != null) {
            chapterPrevButton.setVisible(visible);
            chapterPrevButton.setManaged(visible);
        }
        if (chapterNextButton != null) {
            chapterNextButton.setVisible(visible);
            chapterNextButton.setManaged(visible);
        }
    }

    private void updateSkipTooltips() {
        if (skipBackButton != null) {
            skipBackButton.setTooltip(
                    new Tooltip(Messages.format("player.rewind", DesktopPreferences.getSkipBackSec())));
        }
        if (skipForwardButton != null) {
            skipForwardButton.setTooltip(
                    new Tooltip(Messages.format("player.forward", DesktopPreferences.getSkipForwardSec())));
        }
    }

    private void toggleMute() {
        if (volumeSlider.getValue() > 0) {
            lastVolume = volumeSlider.getValue();
            volumeSlider.setValue(0);
        } else {
            volumeSlider.setValue(lastVolume > 0 ? lastVolume : 1.0);
        }
    }

    private void adjustVolume(double delta) {
        volumeSlider.setValue(Math.max(0, Math.min(1, volumeSlider.getValue() + delta)));
    }

    /** The episode header's refresh: the open subscription only, with the spinner on its button. */
    private void refreshOpenFeed() {
        Feed feed = selectedFeed;
        if (feed == null) {
            setStatus(Messages.get("status.select_podcast_first"));
            return;
        }
        if (episodeRefreshButton.isDisabled()) {
            return;
        }
        setStatus(Messages.format("status.refresh.feed_started", feed.getTitle()));
        spinWhile(episodeRefreshButton, () -> doRefreshFeed(feed));
    }

    /**
     * Swaps the window for the small always-on-top controls; "Show AntennaPod" on them brings
     * the window back. They live in the tray, so without one there is nothing to swap to.
     */
    private void showMiniPlayer() {
        if (!trayActive || !trayManager.showMiniPlayer()) {
            setStatus(Messages.get("status.mini_player.unavailable"));
            return;
        }
        mainStage.hide();
    }

    /** Every keyboard shortcut, as the F1 list shows them. */
    static final String[][] SHORTCUTS = {
            {Messages.get("shortcuts.key.space"), Messages.get("shortcuts.action.play_pause")},
            {Messages.get("shortcuts.key.arrows"), Messages.get("shortcuts.action.seek")},
            {Messages.get("shortcuts.key.ctrl_arrows"), Messages.get("shortcuts.action.previous_next")},
            {Messages.get("shortcuts.key.brackets"), Messages.get("shortcuts.action.speed")},
            {Messages.get("shortcuts.key.ctrl_up_down"), Messages.get("shortcuts.action.volume")},
            {Messages.get("shortcuts.key.m"), Messages.get("shortcuts.action.mute")},
            {Messages.get("shortcuts.key.ctrl_f"), Messages.get("shortcuts.action.search_episodes")},
            {Messages.get("shortcuts.key.ctrl_shift_f"), Messages.get("shortcuts.action.search_all")},
            {Messages.get("shortcuts.key.ctrl_l"), Messages.get("shortcuts.action.search_podcasts")},
            {Messages.get("shortcuts.key.esc"), Messages.get("shortcuts.action.escape")},
            {Messages.get("shortcuts.key.f5"), Messages.get("shortcuts.action.refresh")},
            {Messages.get("shortcuts.key.ctrl_f5"), Messages.get("shortcuts.action.refresh_all")},
            {Messages.get("shortcuts.key.ctrl_comma"), Messages.get("shortcuts.action.settings")},
            {Messages.get("shortcuts.key.ctrl_shift_m"), Messages.get("shortcuts.action.mini_player")},
            {Messages.get("shortcuts.key.f1"), Messages.get("shortcuts.action.this_list")},
    };

    private void showShortcuts() {
        javafx.scene.layout.GridPane grid = new javafx.scene.layout.GridPane();
        grid.setHgap(24);
        grid.setVgap(6);
        grid.setPadding(new Insets(12));
        for (int i = 0; i < SHORTCUTS.length; i++) {
            Label keys = new Label(SHORTCUTS[i][0]);
            keys.setStyle("-fx-font-weight: bold;");
            grid.add(keys, 0, i);
            grid.add(new Label(SHORTCUTS[i][1]), 1, i);
        }
        showModal(Messages.get("shortcuts.title"), grid);
    }

    private static void focusAndSelect(TextField field) {
        field.requestFocus();
        field.selectAll();
    }

    /**
     * Shortcuts that work wherever the focus is, typing included, because they use Ctrl or a
     * function key that a text field has no use for. True when the event was one of them.
     */
    private boolean handleCommandKey(KeyEvent event) {
        boolean ctrl = event.isShortcutDown();
        switch (event.getCode()) {
            case F1:
                showShortcuts();
                return true;
            case F5:
                if (ctrl) {
                    refreshAll();
                } else {
                    refreshOpenFeed();
                }
                return true;
            case F:
                if (ctrl && event.isShiftDown()) {
                    showEpisodeSearch();
                    return true;
                }
                if (ctrl) {
                    focusAndSelect(episodeFilterField);
                    return true;
                }
                return false;
            case L:
                if (ctrl) {
                    focusAndSelect(addField);
                    return true;
                }
                return false;
            case COMMA:
                if (ctrl) {
                    showSettings();
                    return true;
                }
                return false;
            case M:
                if (ctrl && event.isShiftDown()) {
                    showMiniPlayer();
                    return true;
                }
                return false;
            case ESCAPE:
                Node focused = scene != null ? scene.getFocusOwner() : null;
                if (focused instanceof TextField && !((TextField) focused).getText().isEmpty()
                        && (focused == episodeFilterField || focused == feedFilterField
                        || focused == addField)) {
                    ((TextField) focused).clear();
                    return true;
                }
                return false;
            default:
                return false;
        }
    }

    /** Steps the playback speed through the player bar's list, as [ and ] do. */
    private void stepSpeed(int direction) {
        int index = java.util.Arrays.asList(SPEED_OPTIONS).indexOf(speedBox.getValue());
        int next = Math.max(0, Math.min(SPEED_OPTIONS.length - 1, (index < 0 ? 2 : index) + direction));
        if (next != index) {
            // the box's own handler applies and stores the speed
            speedBox.setValue(SPEED_OPTIONS[next]);
            setStatus(Messages.format("status.speed", SPEED_OPTIONS[next]));
        }
    }

    private void handleGlobalKey(KeyEvent event) {
        if (hasModal()) {
            return;
        }
        if (handleCommandKey(event)) {
            event.consume();
            return;
        }
        Node focusOwner = scene != null ? scene.getFocusOwner() : null;
        if (focusOwner instanceof TextInputControl || focusOwner instanceof WebView) {
            return;
        }
        switch (event.getCode()) {
            case OPEN_BRACKET:
                stepSpeed(-1);
                event.consume();
                break;
            case CLOSE_BRACKET:
                stepSpeed(1);
                event.consume();
                break;
            case SPACE:
                togglePlayPause();
                event.consume();
                break;
            case LEFT:
                if (event.isControlDown()) {
                    playback.playPrevious();
                } else {
                    skipBy(-5000);
                }
                event.consume();
                break;
            case RIGHT:
                if (event.isControlDown()) {
                    playback.playNext();
                } else {
                    skipBy(5000);
                }
                event.consume();
                break;
            case UP:
                if (event.isControlDown()) {
                    adjustVolume(0.05);
                    event.consume();
                }
                break;
            case DOWN:
                if (event.isControlDown()) {
                    adjustVolume(-0.05);
                    event.consume();
                }
                break;
            case M:
                if (!event.isControlDown() && !event.isAltDown() && !event.isShiftDown()) {
                    toggleMute();
                    event.consume();
                }
                break;
            // the media keys normally never get this far: whoever registered them system-wide
            // takes them first, and while that is this app they arrive as hotkeys instead. This
            // is what is left when another player holds them and this window has the focus
            case PLAY:
            case PAUSE:
                togglePlayPause();
                event.consume();
                break;
            case TRACK_NEXT:
                playback.playNext();
                event.consume();
                break;
            case TRACK_PREV:
                playback.playPrevious();
                event.consume();
                break;
            case STOP:
                playback.stop();
                event.consume();
                break;
            default:
                break;
        }
    }

    /**
     * Claims the keyboard's media keys so they reach this app while another window has the focus.
     *
     * <p>Windows hands a media key to whichever process registered it and to no other, so without
     * this the keys only ever reached whatever player claimed them first. A key another player
     * already holds is left with them rather than fought over.
     */
    /**
     * Runs a system media card press on the application thread, unless media keys are switched
     * off — the one switch covers both transports, so the settings checkbox keeps working now
     * that presses arrive through the card instead of claimed hotkeys.
     */
    private void onCardButton(Runnable action) {
        if (!DesktopPreferences.getMediaKeysEnabled()) {
            return;
        }
        Platform.runLater(() -> {
            try {
                action.run();
            } catch (Throwable t) {
                t.printStackTrace();
            }
        });
    }

    private void startMediaKeys() {
        if (!MediaKeys.isEnabled() || !DesktopPreferences.getMediaKeysEnabled()) {
            return;
        }
        if (SmtcManager.isEnabled() && !smtcUnavailable) {
            // Presses arrive through the system media card, which Windows routes to whichever
            // player is currently active — this episode while it plays, the other app while it
            // does. Claiming the keys here would steal them from other players and fire twice.
            return;
        }
        mediaKeys = new MediaKeys(new MediaKeys.Callbacks() {
            @Override
            public void onPlayPause() {
                togglePlayPause();
            }

            @Override
            public void onNext() {
                playback.playNext();
            }

            @Override
            public void onPrevious() {
                playback.playPrevious();
            }

            @Override
            public void onStop() {
                playback.stop();
            }
        });
        mediaKeys.start();
    }

    /** Turns the media keys on or off from the settings, without a restart. */
    private void setMediaKeysEnabled(boolean enabled) {
        DesktopPreferences.setMediaKeysEnabled(enabled);
        if (enabled) {
            if (mediaKeys == null) {
                startMediaKeys();
            }
            return;
        }
        if (mediaKeys != null) {
            mediaKeys.stop();
            mediaKeys = null;
        }
    }

    private void updateTransportEnabled() {
        FeedMedia current = playback.getCurrentMedia();
        boolean hasMedia = current != null;
        boolean hasChapters = hasMedia && current.getItem() != null
                && current.getItem().getChapters() != null
                && !current.getItem().getChapters().isEmpty();
        if (seekSlider != null) {
            seekSlider.setDisable(!hasMedia);
            if (!hasMedia) {
                seekSlider.setValue(0);
            }
        }
        if (skipBackButton != null) {
            skipBackButton.setDisable(!hasMedia);
        }
        if (skipForwardButton != null) {
            skipForwardButton.setDisable(!hasMedia);
        }
        if (!hasMedia) {
            updateTimeLabels(0, 0);
        }
        setChapterButtonsVisible(hasChapters);
        if (!hasChapters && chapterLabel != null) {
            chapterLabel.setText("");
            chapterLabel.setVisible(false);
            chapterLabel.setManaged(false);
        }
    }

    private Region buildLoadingPulse(double width, double height,
            javafx.beans.binding.DoubleExpression available) {
        Region pulse = new Region();
        pulse.getStyleClass().add("loading-pulse");
        pulse.setMouseTransparent(true);
        pulse.setMinSize(width, height);
        pulse.setPrefSize(width, height);
        pulse.setMaxSize(width, height);
        pulse.translateXProperty().bind(loadingPhase.multiply(available.subtract(width)));
        StackPane.setAlignment(pulse, Pos.CENTER_LEFT);
        return pulse;
    }

    private void updateLoadingIndicator() {
        boolean loading = loadingMediaId != -1;
        if (loadingSpinner != null) {
            loadingSpinner.setVisible(loading);
        }
        if (loading) {
            if (loadingPulseTimeline == null) {
                loadingPulseTimeline = new javafx.animation.Timeline(
                        new javafx.animation.KeyFrame(javafx.util.Duration.seconds(1.1),
                                new javafx.animation.KeyValue(loadingPhase, 1.0)));
                loadingPulseTimeline.setAutoReverse(true);
                loadingPulseTimeline.setCycleCount(javafx.animation.Timeline.INDEFINITE);
            }
            if (loadingPulseTimeline.getStatus() != javafx.animation.Animation.Status.RUNNING) {
                loadingPulseTimeline.play();
            }
        } else if (loadingPulseTimeline != null) {
            loadingPulseTimeline.stop();
            loadingPhase.set(0);
        }
        if (seekPulse != null) {
            seekPulse.setVisible(loading);
        }
    }

    private void updateTimeLabels(int positionMs, int durationMs) {
        int clamped = durationMs > 0 ? Math.min(positionMs, durationMs) : Math.max(positionMs, 0);
        elapsedLabel.setText(formatDuration(clamped));
        if (durationMs <= 0) {
            totalLabel.setText("--:--");
        } else if (showRemainingTime) {
            totalLabel.setText("-" + formatDuration(Math.max(durationMs - clamped, 0)));
        } else {
            totalLabel.setText(formatDuration(durationMs));
        }
    }

    /**
     * Shows a status message, fading it out a minute later so stale notes stop shouting.
     * A new message restores full opacity and restarts the minute.
     */
    private void setStatus(String message) {
        synchronized (statusLog) {
            statusLog.addFirst(new StatusEntry(System.currentTimeMillis(), message));
            while (statusLog.size() > MAX_STATUS_LOG) {
                statusLog.removeLast();
            }
        }
        Platform.runLater(() -> {
            statusLabel.setText(message);
            String tip = message + "\n\n" + Messages.get("status.click_to_copy");
            if (statusLabel.getTooltip() == null) {
                statusLabel.setTooltip(new Tooltip(tip));
            } else {
                statusLabel.getTooltip().setText(tip);
            }
            statusLabel.setOpacity(1);
            restartStatusFade();
        });
    }

    private void restartStatusFade() {
        if (statusFadeOut != null) {
            statusFadeOut.stop();
        }
        if (statusFadeDelay == null) {
            statusFadeDelay = new PauseTransition(Duration.seconds(STATUS_FADE_SECONDS));
            statusFadeDelay.setOnFinished(event -> {
                statusFadeOut = new FadeTransition(Duration.millis(1000), statusLabel);
                statusFadeOut.setFromValue(1);
                statusFadeOut.setToValue(0);
                statusFadeOut.play();
            });
        }
        statusFadeDelay.playFromStart();
    }

    /**
     * Re-reads the feed counts in the background and redraws the feed list with them. Calls made
     * while one is still waiting to run share it: it reads the database when it starts.
     */
    private void refreshFeedCounts() {
        if (background == null || !feedCountsQueued.compareAndSet(false, true)) {
            return;
        }
        background.submit(() -> {
            feedCountsQueued.set(false);
            try {
                Map<Long, int[]> counts = database.getFeedCounts();
                Platform.runLater(() -> {
                    feedCounts.clear();
                    feedCounts.putAll(counts);
                    feedList.refresh();
                });
            } catch (Exception e) {
                // keep showing the last counts
            }
        });
    }

    private void reloadFeeds(Long selectFeedId) {
        background.submit(() -> {
            try {
                List<Feed> all = database.getAllFeeds();
                Map<Long, Long> lastPlayed = database.getFeedLastPlayedTimes();
                Map<Long, int[]> counts = database.getFeedCounts();
                Map<Long, java.util.SortedSet<String>> tags = database.getFeedTags();
                Platform.runLater(() -> {
                    applyFeedTags(tags);
                    feedCounts.clear();
                    feedCounts.putAll(counts);
                    feedLastPlayed.clear();
                    feedLastPlayed.putAll(lastPlayed);
                    FeedSorter.sortByLastPlayed(all, lastPlayed);
                    // replacing the items drops the selection (fresh instances), so the
                    // wanted subscription is remembered by id: an explicit one wins,
                    // otherwise whatever the user has open stays open
                    Long keepId = selectFeedId;
                    if (keepId == null) {
                        Feed selected = feedList.getSelectionModel().getSelectedItem();
                        if (selected != null) {
                            keepId = selected.getId();
                        } else if (selectedFeed != null) {
                            keepId = selectedFeed.getId();
                        }
                    }
                    feeds.setAll(all);
                    feedList.refresh();
                    boolean kept = false;
                    if (keepId != null) {
                        for (Feed feed : all) {
                            if (feed.getId() == keepId) {
                                feedList.getSelectionModel().select(feed);
                                kept = true;
                                break;
                            }
                        }
                    }
                    // nothing wanted, or the wanted feed is gone (unsubscribed): open the first
                    if (!kept && !all.isEmpty()
                            && feedList.getSelectionModel().getSelectedItem() == null) {
                        feedList.getSelectionModel().selectFirst();
                    }
                });
            } catch (Exception e) {
                setStatus(Messages.format("status.feeds.load_failed", e.getMessage()));
            }
        });
    }

    private void markFeedPlayed(long feedId) {
        feedLastPlayed.put(feedId, System.currentTimeMillis());
        resortFeeds();
    }

    private void resortFeeds() {
        List<Feed> sorted = new ArrayList<>(feeds);
        FeedSorter.sortByLastPlayed(sorted, feedLastPlayed);
        if (sorted.equals(new ArrayList<>(feeds))) {
            return;
        }
        Feed selected = feedList.getSelectionModel().getSelectedItem();
        feeds.setAll(sorted);
        if (selected != null) {
            feedList.getSelectionModel().select(selected);
        }
    }

    private void loadEpisodes(Feed feed) {
        if (!Platform.isFxApplicationThread()) {
            Platform.runLater(() -> loadEpisodes(feed));
            return;
        }
        selectedFeed = feed;
        long load = ++episodeLoadGeneration;
        background.submit(() -> {
            try {
                Feed full = database.getFeed(feed.getId());
                FeedPrefs prefs = database.getFeedPrefs(feed.getId());
                List<FeedItem> items = full.getItems() != null
                        ? new ArrayList<>(full.getItems()) : new ArrayList<>();
                EpisodeSorter.sort(items, prefs.sortCode);
                Map<Long, Integer> synced = database.getSyncedPositions(feed.getId());
                Platform.runLater(() -> {
                    if (load != episodeLoadGeneration) {
                        // a big feed that finished loading after a small one clicked since would
                        // otherwise replace it, leaving one feed highlighted and another listed
                        return;
                    }
                    feedTitleLabel.setText(full.getTitle() != null ? full.getTitle() : full.getDownloadUrl());
                    sortBoxProgrammatic = true;
                    try {
                        sortBox.setValue(sortLabel(prefs.sortCode));
                    } finally {
                        sortBoxProgrammatic = false;
                    }
                    syncedPositions.clear();
                    syncedPositions.putAll(synced);
                    episodes.setAll(items);
                    // a big feed opens at the top; bring back where you left off instead
                    scrollToLeftOff();
                });
            } catch (Exception e) {
                setStatus(Messages.format("status.episodes.load_failed", e.getMessage()));
            }
        });
    }

    private void subscribe(String url) {
        if (url.isEmpty()) {
            return;
        }
        setStatus(Messages.format("status.subscribe.started", url));
        background.submit(() -> doSubscribe(url));
    }

    private void startSubscribe(Button button, String rawUrl) {
        String url = rawUrl.trim();
        if (url.isEmpty()) {
            return;
        }
        setStatus(Messages.format("status.subscribe.started", url));
        spinWhile(button, () -> doSubscribe(url));
    }

    private void doSubscribe(String url) {
        try {
            subscribeAndShow(url, null);
        } catch (Exception e) {
            if (FeedUpdater.isAuthRequired(e)) {
                setStatus(Messages.get("status.subscribe.login_needed"));
                Platform.runLater(() -> showFeedLoginModal(url));
                return;
            }
            setStatus(Messages.format("status.subscribe.failed", e.getMessage()));
        }
    }

    private void subscribeAndShow(String url, FeedCredentials.Login login) throws Exception {
        Feed feed = login != null ? feedUpdater.subscribe(url, login) : feedUpdater.subscribe(url);
        setStatus(Messages.format("status.subscribed", feed.getTitle()));
        reloadFeeds(feed.getId());
    }

    /**
     * Asks for the login of a password-protected feed and subscribes with it. The modal stays
     * open while it tries and after a rejected login, so the fields keep their focus and text;
     * closing and reopening it handed the focus back to the toolbar field.
     */
    private void showFeedLoginModal(String url) {
        Label intro = new Label(Messages.get("login.intro"));
        intro.setWrapText(true);
        Label address = new Label(FeedCredentials.withoutUserInfo(url));
        address.getStyleClass().add("muted-label");
        address.setWrapText(true);
        TextField userField = new TextField();
        userField.setPromptText(Messages.get("login.username.prompt"));
        javafx.scene.control.PasswordField passField = new javafx.scene.control.PasswordField();
        passField.setPromptText(Messages.get("login.password.prompt"));
        Button subscribeButton = new Button(Messages.get("common.subscribe"), Icons.add());
        subscribeButton.setDefaultButton(true);
        VBox pane = new VBox(10, intro, address, userField, passField);
        Runnable submit = () -> {
            String user = userField.getText().trim();
            if (user.isEmpty()) {
                userField.requestFocus();
                return;
            }
            if (subscribeButton.isDisabled()) {
                return;
            }
            FeedCredentials.Login login = new FeedCredentials.Login(user, passField.getText());
            setStatus(Messages.format("status.subscribe.started", FeedCredentials.withoutUserInfo(url)));
            spinWhile(subscribeButton, () -> {
                try {
                    subscribeAndShow(url, login);
                    Platform.runLater(() -> appShell.getChildren().remove(modalOverlayOf(pane)));
                } catch (Exception e) {
                    boolean rejected = FeedUpdater.isAuthRequired(e);
                    setStatus(rejected ? Messages.get("status.subscribe.login_rejected")
                            : Messages.format("status.subscribe.failed", e.getMessage()));
                    Platform.runLater(() -> {
                        intro.setText(rejected
                                ? Messages.get("login.rejected")
                                : Messages.format("login.failed", e.getMessage()));
                        passField.selectAll();
                        passField.requestFocus();
                    });
                }
            });
        };
        subscribeButton.setOnAction(event -> submit.run());
        passField.setOnAction(event -> submit.run());
        userField.setOnAction(event -> passField.requestFocus());
        HBox buttons = new HBox(8, subscribeButton);
        buttons.setAlignment(Pos.CENTER_RIGHT);
        pane.getChildren().add(buttons);
        pane.setPadding(new Insets(12));
        showModal(Messages.get("login.title"), pane);
        Platform.runLater(userField::requestFocus);
    }

    /** The modal overlay a piece of modal content sits in, or null once it is closed. */
    private Node modalOverlayOf(Node content) {
        for (Node node = content; node != null; node = node.getParent()) {
            if (node.getStyleClass().contains("modal-overlay")) {
                return node;
            }
        }
        return null;
    }

    private void refreshFeed(Feed feed) {
        setStatus(Messages.format("status.refresh.feed_started", feed.getTitle()));
        background.submit(() -> doRefreshFeed(feed));
    }

    private void doRefreshFeed(Feed feed) {
        try {
            List<FeedItem> added = feedUpdater.refresh(feed);
            autoDownloadNew(feed, added);
            setStatus(Messages.format("status.refresh.feed_done", feed.getTitle(), added.size()));
            reloadEpisodesIfShowing(feed);
            refreshFeedCounts();
        } catch (Exception e) {
            setStatus(FeedUpdater.isAuthRequired(e)
                    ? Messages.format("status.refresh.login_needed", feed.getTitle())
                    : Messages.format("status.refresh.failed", e.getMessage()));
        }
    }

    /** Reloads the episode list if it still shows this feed; the user may have moved on. */
    private void reloadEpisodesIfShowing(Feed feed) {
        Platform.runLater(() -> {
            if (selectedFeed != null && selectedFeed.getId() == feed.getId()) {
                loadEpisodes(selectedFeed);
            }
        });
    }

    private void autoDownloadNew(Feed feed, List<FeedItem> newItems) {
        if (newItems.isEmpty()) {
            return;
        }
        try {
            FeedPrefs prefs = database.getFeedPrefs(feed.getId());
            boolean globalDefault = DesktopPreferences.getAutoDownloadDefault();
            for (FeedItem item : newItems) {
                if (item.getMedia() == null || downloader.isDownloading(item.getMedia().getId())) {
                    continue;
                }
                if (Automation.shouldAutoDownload(item, prefs, globalDefault)) {
                    downloader.enqueue(item.getMedia(), new QuietDownloadListener(item));
                }
            }
        } catch (Exception e) {
            setStatus(Messages.format("status.autodownload.failed", e.getMessage()));
        }
    }

    private void refreshAll() {
        setStatus(Messages.get("status.refresh.all_started"));
        background.submit(this::doRefreshAll);
    }

    private void doRefreshAll() {
        try {
            List<FeedUpdater.RefreshResult> results = feedUpdater.refreshAll();
            int total = 0;
            int errors = 0;
            for (FeedUpdater.RefreshResult result : results) {
                if (result.error != null) {
                    errors++;
                } else {
                    total += result.newEpisodes.size();
                    autoDownloadNew(result.feed, result.newEpisodes);
                }
            }
            setStatus(errors > 0 ? Messages.format("status.refresh.done_with_errors", total, errors)
                    : Messages.format("status.refresh.done", total));
            notifyNewEpisodes(results);
            refreshFeedCounts();
            Platform.runLater(() -> {
                Feed selected = feedList.getSelectionModel().getSelectedItem();
                if (selected != null) {
                    loadEpisodes(selected);
                }
            });
        } catch (Exception e) {
            setStatus(Messages.format("status.refresh.failed", e.getMessage()));
        }
    }

    /**
     * One Windows notification for a refresh of every subscription that brought new episodes.
     * It goes through the tray icon, so there is none without one; a refresh of the podcast
     * being looked at says nothing beyond the status line.
     */
    private void notifyNewEpisodes(List<FeedUpdater.RefreshResult> results) {
        if (!trayActive || !DesktopPreferences.getNotifyNewEpisodes()) {
            return;
        }
        NewEpisodesNotice notice = NewEpisodesNotice.of(results);
        if (notice != null) {
            trayManager.notify(notice.title, notice.text);
        }
    }

    private void unsubscribe(Feed feed) {
        background.submit(() -> {
            try {
                feedUpdater.unsubscribe(feed.getId());
                setStatus(Messages.format("status.unsubscribed", feed.getTitle()));
                Platform.runLater(() -> {
                    // only the open feed's list goes; unsubscribing another one from its context
                    // menu leaves what is shown alone. Forgetting it also stops later reloads
                    // (sync, mark seen, sort changes) from loading a feed that no longer exists.
                    if (selectedFeed != null && selectedFeed.getId() == feed.getId()) {
                        selectedFeed = null;
                        episodeLoadGeneration++;
                        episodes.clear();
                        feedTitleLabel.setText(Messages.get("episodes.select_podcast"));
                    }
                });
                reloadFeeds(null);
            } catch (Exception e) {
                setStatus(Messages.format("status.unsubscribe.failed", e.getMessage()));
            }
        });
    }

    private void markFeedSeen(Feed feed) {
        background.submit(() -> {
            try {
                int cleared = database.clearNewFlags(feed.getId());
                if (cleared == 0) {
                    setStatus(Messages.format("status.mark_seen.none_in_feed", feed.getTitle()));
                } else {
                    setStatus(Messages.format("status.mark_seen.done", episodeCountText(cleared)));
                }
                reloadEpisodesIfShowing(feed);
                refreshFeedCounts();
            } catch (Exception e) {
                setStatus(Messages.format("status.mark_seen.failed", e.getMessage()));
            }
        });
    }

    private void markAllSeen() {
        background.submit(() -> {
            try {
                int cleared = database.clearAllNewFlags();
                if (cleared == 0) {
                    setStatus(Messages.get("status.mark_seen.none"));
                } else {
                    setStatus(Messages.format("status.mark_seen.done", episodeCountText(cleared)));
                }
                if (selectedFeed != null) {
                    reloadEpisodesIfShowing(selectedFeed);
                }
                refreshFeedCounts();
            } catch (Exception e) {
                setStatus(Messages.format("status.mark_seen.failed", e.getMessage()));
            }
        });
    }

    private void importOpml() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(Messages.get("opml.import.title"));
        chooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter(Messages.get("opml.filter"), "*.opml", "*.xml"));
        File file = chooser.showOpenDialog(feedList.getScene().getWindow());
        if (file == null) {
            return;
        }
        setStatus(Messages.format("status.opml.importing", file.getName()));
        background.submit(() -> {
            // UTF-8 rather than the platform charset; lenient, so a stray byte from an exporter
            // that wrote Latin-1 is replaced instead of failing the whole import
            try (java.io.Reader reader = new java.io.InputStreamReader(
                    new java.io.FileInputStream(file), java.nio.charset.StandardCharsets.UTF_8)) {
                OpmlImporter.ImportResult result =
                        new OpmlImporter(database, feedUpdater).importFromReader(reader);
                setStatus(result.failed.isEmpty()
                        ? Messages.format("status.opml.imported", result.imported.size())
                        : Messages.format("status.opml.imported_with_errors", result.imported.size(),
                                result.failed.size()));
                reloadFeeds(null);
            } catch (Exception e) {
                setStatus(Messages.format("status.opml.import_failed", e.getMessage()));
            }
        });
    }

    private void exportOpml() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(Messages.get("opml.export.title"));
        chooser.setInitialFileName("antennapod-subscriptions.opml");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(Messages.get("opml.filter"), "*.opml"));
        File file = chooser.showSaveDialog(feedList.getScene().getWindow());
        if (file == null) {
            return;
        }
        background.submit(() -> {
            // UTF-8, as the document declares; FileWriter would use the platform charset
            try (java.io.Writer writer = java.nio.file.Files.newBufferedWriter(
                    file.toPath(), java.nio.charset.StandardCharsets.UTF_8)) {
                new OpmlImporter(database, feedUpdater).exportToWriter(writer);
                setStatus(Messages.format("status.opml.exported", file.getName()));
            } catch (Exception e) {
                setStatus(Messages.format("status.opml.export_failed", e.getMessage()));
            }
        });
    }

    /**
     * Adds a folder of the user's audio files as a subscription. The files play from where they
     * are and are never moved, changed or deleted by the app; a refresh picks up added files.
     */
    private void addLocalFolder() {
        javafx.stage.DirectoryChooser chooser = new javafx.stage.DirectoryChooser();
        chooser.setTitle(Messages.get("local_folder.chooser_title"));
        File folder = chooser.showDialog(feedList.getScene().getWindow());
        if (folder == null) {
            return;
        }
        setStatus(Messages.format("status.local_folder.adding", folder.getName()));
        background.submit(() -> {
            try {
                Feed feed = feedUpdater.subscribeLocalFolder(folder);
                int episodes = feed.getItems() != null ? feed.getItems().size() : 0;
                setStatus(episodes == 0
                        ? Messages.format("status.local_folder.added_empty", feed.getTitle())
                        : Messages.format("status.local_folder.added", feed.getTitle(), episodeCountText(episodes)));
                reloadFeeds(feed.getId());
            } catch (Exception e) {
                setStatus(Messages.format("status.local_folder.failed", e.getMessage()));
            }
        });
    }

    private void backUpProfile(Button button) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(Messages.get("backup.chooser_title"));
        chooser.setInitialFileName("AntennaPod-backup-"
                + new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(new Date()) + ".zip");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(Messages.get("backup.filter"), "*.zip"));
        File file = chooser.showSaveDialog(feedList.getScene().getWindow());
        if (file == null) {
            return;
        }
        setStatus(Messages.format("status.backup.writing", file.getName()));
        spinWhile(button, () -> {
            try {
                ProfileBackup.write(database, file, appVersion());
                setStatus(Messages.format("status.backup.done", file.getAbsolutePath()));
            } catch (Exception e) {
                setStatus(Messages.format("status.backup.failed", e.getMessage()));
            }
        });
    }

    private void restoreProfile(Button button) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(Messages.get("backup.restore.chooser_title"));
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(Messages.get("backup.filter"), "*.zip"));
        File file = chooser.showOpenDialog(feedList.getScene().getWindow());
        if (file == null) {
            return;
        }
        File dataDir = DesktopPreferences.getDataDir();
        spinWhile(button, () -> {
            try {
                ProfileBackup.Info info = ProfileBackup.stage(file, dataDir);
                Platform.runLater(() -> confirmRestore(file, info, dataDir));
            } catch (Exception e) {
                setStatus(Messages.format("status.backup.restore_file_failed", file.getName(), e.getMessage()));
            }
        });
    }

    /** The backup checked out and is staged; applying it takes a restart. */
    private void confirmRestore(File file, ProfileBackup.Info info, File dataDir) {
        String made = info.createdMs > 0
                ? new SimpleDateFormat("d MMM yyyy HH:mm", Locale.US).format(new Date(info.createdMs))
                : Messages.get("backup.restore.unknown_date");
        String subscriptions = info.feedCount == 1 ? Messages.get("count.subscription.one")
                : Messages.format("count.subscription.other", info.feedCount);
        Label text = new Label((info.appVersion.isEmpty()
                ? Messages.format("backup.restore.confirm", file.getName(), made, subscriptions)
                : Messages.format("backup.restore.confirm_version", file.getName(), made, info.appVersion,
                        subscriptions))
                + "\n\n" + Messages.format("backup.restore.warning", ProfileBackup.REPLACED_DB));
        text.setWrapText(true);
        Button restart = new Button(Messages.get("backup.restore.restart"));
        restart.setDefaultButton(true);
        Button cancel = new Button(Messages.get("common.cancel"));
        cancel.setCancelButton(true);
        VBox pane = new VBox(12, text);
        restart.setOnAction(event -> restartApp());
        cancel.setOnAction(event -> {
            ProfileBackup.cancelPending(dataDir);
            appShell.getChildren().remove(modalOverlayOf(pane));
            setStatus(Messages.get("status.backup.restore_cancelled"));
        });
        HBox buttons = new HBox(8, cancel, restart);
        buttons.setAlignment(Pos.CENTER_RIGHT);
        pane.getChildren().add(buttons);
        pane.setPadding(new Insets(12));
        showModal(Messages.get("backup.restore.title"), pane);
    }

    /**
     * Starts a new instance with the same command line and shuts this one down. The new one is
     * marked so it waits for this one's instance lock instead of reporting "already running".
     */
    private void restartApp() {
        try {
            ProcessBuilder builder = new ProcessBuilder(relaunchCommand());
            builder.environment().put(RESTART_ENV, "1");
            builder.redirectErrorStream(true);
            builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            Process next = builder.start();
            // one that dies at once (a bad command line) must not leave the user with no app
            if (next.waitFor(1500, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                setStatus(Messages.get("status.restart.failed"));
                return;
            }
        } catch (Exception e) {
            setStatus(Messages.format("status.restart.failed_detail", e.getMessage()));
            return;
        }
        shutdown();
    }

    /**
     * How to start this app again: the installed or unzipped exe when jpackage launched us,
     * otherwise the same JVM with its options, class path and main class. (Windows does not
     * report a process's arguments through ProcessHandle, so they are rebuilt from the JVM.)
     */
    static List<String> relaunchCommand() {
        List<String> line = new ArrayList<>();
        String exe = System.getProperty("jpackage.app-path");
        if (exe != null && !exe.isEmpty()) {
            line.add(exe);
            return line;
        }
        line.add(new File(System.getProperty("java.home"), "bin" + File.separator + "java").getPath());
        line.addAll(java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments());
        line.add("-cp");
        line.add(System.getProperty("java.class.path"));
        String command = System.getProperty("sun.java.command", Launcher.class.getName());
        line.addAll(java.util.Arrays.asList(command.trim().split("\\s+")));
        return line;
    }

    private void toggleFavorite(FeedItem item) {
        setFavorites(actionTargets(item), !item.isTagged(FeedItem.TAG_FAVORITE));
    }

    private void setFavorites(List<FeedItem> items, boolean favorite) {
        background.submit(() -> {
            try {
                for (FeedItem item : items) {
                    database.setFavorite(item.getId(), favorite);
                    if (favorite) {
                        item.addTag(FeedItem.TAG_FAVORITE);
                    } else {
                        item.removeTag(FeedItem.TAG_FAVORITE);
                    }
                }
                setStatus(favorite ? Messages.format("status.favorites.added", episodeCountText(items))
                        : Messages.format("status.favorites.removed", episodeCountText(items)));
                Platform.runLater(this::refilterEpisodes);
            } catch (Exception e) {
                setStatus(Messages.format("status.favorites.update_failed", e.getMessage()));
            }
        });
    }

    private void showFavorites() {
        background.submit(() -> {
            try {
                List<FeedItem> favorites = database.getFavorites();
                Platform.runLater(() -> {
                    ObservableList<FeedItem> items = FXCollections.observableArrayList(favorites);
                    ListView<FeedItem> list = new ListView<>(items);
                    list.setCellFactory(view -> fullWidthCell(new ListCell<>() {
                        @Override
                        protected void updateItem(FeedItem item, boolean empty) {
                            super.updateItem(item, empty);
                            if (empty || item == null) {
                                setText(null);
                                setGraphic(null);
                                return;
                            }
                            Label title = new Label(item.getTitle());
                            title.setWrapText(true);
                            Button removeButton = new Button(Messages.get("favorites.unfavorite"));
                            removeButton.setOnAction(event -> background.submit(() -> {
                                try {
                                    database.setFavorite(item.getId(), false);
                                    Platform.runLater(() -> {
                                        items.remove(item);
                                        // the open list holds its own copies of the episodes
                                        for (FeedItem shown : episodes) {
                                            if (shown.getId() == item.getId()) {
                                                shown.removeTag(FeedItem.TAG_FAVORITE);
                                            }
                                        }
                                        episodeList.refresh();
                                    });
                                } catch (Exception e) {
                                    setStatus(Messages.format("status.favorite.update_failed", e.getMessage()));
                                }
                            }));
                            HBox row = new HBox(8, title, removeButton);
                            HBox.setHgrow(title, Priority.ALWAYS);
                            setGraphic(row);
                            setText(null);
                        }
                    }));
                    list.setOnMouseClicked(event -> {
                        if (event.getClickCount() == 2) {
                            FeedItem selected = list.getSelectionModel().getSelectedItem();
                            if (selected != null && selected.getMedia() != null) {
                                playback.play(selected, new ArrayList<>(items));
                            }
                        }
                    });
                    VBox pane = new VBox(8, list);
                    pane.setPadding(new Insets(8));
                    showSidebar(Messages.get("favorites.title"), pane);
                });
            } catch (Exception e) {
                setStatus(Messages.format("status.favorites.load_failed", e.getMessage()));
            }
        });
    }

    /** Searches the titles and show notes of every subscription's episodes, as you type. */
    private void showEpisodeSearch() {
        TextField query = new TextField();
        query.setPromptText(Messages.get("search_all.prompt"));
        ObservableList<FeedItem> items = FXCollections.observableArrayList();
        Label summary = new Label(Messages.get("search_all.hint"));
        summary.getStyleClass().add("muted-label");
        summary.setWrapText(true);
        ListView<FeedItem> list = new ListView<>(items);
        list.setPlaceholder(new Label(""));
        SimpleDateFormat dateFormat = new SimpleDateFormat("d MMM yyyy", Locale.US);
        list.setCellFactory(view -> fullWidthCell(new ListCell<>() {
            @Override
            protected void updateItem(FeedItem item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                    return;
                }
                Label title = new Label(item.getTitle());
                title.setWrapText(true);
                StringBuilder meta = new StringBuilder();
                String feedTitle = feedTitleOf(item.getFeedId());
                if (feedTitle != null) {
                    meta.append(feedTitle);
                }
                if (item.getPubDate() != null) {
                    appendMeta(meta, dateFormat.format(item.getPubDate()));
                }
                if (item.isPlayed()) {
                    appendMeta(meta, Messages.get("episodes.meta.played"));
                }
                Label metaLabel = new Label(meta.toString());
                metaLabel.getStyleClass().add("muted-label");
                VBox texts = new VBox(2, title, metaLabel);
                texts.setMinWidth(0);
                HBox.setHgrow(texts, Priority.ALWAYS);
                Button play = iconButton(Icons.play(), Messages.get("common.play"));
                play.setDisable(item.getMedia() == null);
                play.setOnAction(event -> playback.play(item, new ArrayList<>(items)));
                Button queue = iconButton(Icons.queueAdd(), Messages.get("common.add_to_queue"));
                queue.setOnAction(event -> enqueueItems(List.of(item)));
                Button open = iconButton(Icons.navigateAfter(), Messages.get("search_all.open_in_podcast"));
                open.setOnAction(event -> openInPodcast(item));
                HBox row = new HBox(8, texts, play, queue, open);
                row.setAlignment(Pos.CENTER_LEFT);
                setGraphic(row);
                setText(null);
            }
        }));
        list.setOnMouseClicked(event -> {
            if (event.getClickCount() == 2) {
                FeedItem selected = list.getSelectionModel().getSelectedItem();
                if (selected != null && selected.getMedia() != null) {
                    playback.play(selected, new ArrayList<>(items));
                }
            }
        });
        // one query per pause in typing, and a slow one never overwrites a newer one's results
        int[] generation = {0};
        PauseTransition debounce = new PauseTransition(Duration.millis(250));
        debounce.setOnFinished(event -> {
            String text = query.getText().trim();
            int mine = ++generation[0];
            if (text.isEmpty()) {
                items.clear();
                summary.setText(Messages.get("search_all.hint"));
                return;
            }
            background.submit(() -> {
                try {
                    List<FeedItem> found = database.searchItems(text, EPISODE_SEARCH_LIMIT);
                    Platform.runLater(() -> {
                        if (mine != generation[0]) {
                            return;
                        }
                        items.setAll(found);
                        summary.setText(found.isEmpty() ? Messages.format("search_all.no_match", text)
                                : found.size() >= EPISODE_SEARCH_LIMIT
                                        ? Messages.format("search_all.limited", EPISODE_SEARCH_LIMIT)
                                        : Messages.format("search_all.found", episodeCountText(found.size())));
                    });
                } catch (Exception e) {
                    setStatus(Messages.format("status.search.failed", e.getMessage()));
                }
            });
        });
        query.textProperty().addListener((obs, oldText, newText) -> debounce.playFromStart());
        query.setOnAction(event -> {
            debounce.stop();
            debounce.getOnFinished().handle(null);
        });
        VBox pane = new VBox(8, query, summary, list);
        pane.setPadding(new Insets(8));
        VBox.setVgrow(list, Priority.ALWAYS);
        showSidebar(Messages.get("search_all.title"), pane);
        Platform.runLater(query::requestFocus);
    }

    private static final int EPISODE_SEARCH_LIMIT = 200;

    /** Opens the episode's subscription with its list narrowed to that episode. */
    private void openInPodcast(FeedItem item) {
        for (Feed feed : feeds) {
            if (feed.getId() == item.getFeedId()) {
                episodeFilterField.setText(item.getTitle() != null ? item.getTitle() : "");
                episodeStateBox.setValue(EpisodeFilter.ALL);
                feedFilterField.clear();
                feedList.getSelectionModel().select(feed);
                feedList.scrollTo(feed);
                return;
            }
        }
        setStatus(Messages.get("status.podcast_not_subscribed"));
    }

    private void showDownloads() {
        background.submit(() -> {
            try {
                List<FeedItem> rows = loadDownloadRows();
                Platform.runLater(() -> showDownloadsPane(rows));
            } catch (Exception e) {
                setStatus(Messages.format("status.downloads.load_failed", e.getMessage()));
            }
        });
    }

    /** Running and waiting transfers first, then the finished downloads, newest first. */
    private List<FeedItem> loadDownloadRows() throws Exception {
        List<FeedItem> rows = new ArrayList<>();
        java.util.Set<Long> seen = new java.util.HashSet<>();
        for (long mediaId : downloader.activeMediaIds()) {
            FeedItem item = database.getItemOfMedia(mediaId);
            if (item != null && seen.add(mediaId)) {
                rows.add(item);
            }
        }
        for (FeedItem item : database.getDownloadedItems()) {
            if (item.getMedia() != null && seen.add(item.getMedia().getId())) {
                rows.add(item);
            }
        }
        return rows;
    }

    private void showDownloadsPane(List<FeedItem> rows) {
        ObservableList<FeedItem> items = FXCollections.observableArrayList(rows);
        Label summary = new Label();
        summary.getStyleClass().add("muted-label");
        Runnable updateSummary = () -> {
            int count = 0;
            long bytes = 0;
            for (FeedItem item : items) {
                FeedMedia media = item.getMedia();
                if (media != null && media.localFileAvailable()) {
                    count++;
                    bytes += Math.max(media.getSize(), 0);
                }
            }
            summary.setText(Messages.format("downloads.summary", episodeCountText(count), formatSize(bytes)));
        };
        updateSummary.run();
        Runnable reload = () -> background.submit(() -> {
            try {
                List<FeedItem> fresh = loadDownloadRows();
                Platform.runLater(() -> {
                    items.setAll(fresh);
                    updateSummary.run();
                });
            } catch (Exception e) {
                setStatus(Messages.format("status.downloads.load_failed", e.getMessage()));
            }
        });
        // the open episode list holds its own copies of these episodes, so it re-reads them too
        Runnable afterDelete = () -> {
            reload.run();
            if (selectedFeed != null) {
                loadEpisodes(selectedFeed);
            }
        };
        ListView<FeedItem> list = new ListView<>(items);
        list.setPlaceholder(new Label(Messages.get("downloads.empty")));
        list.setCellFactory(view -> fullWidthCell(new ListCell<>() {
            @Override
            protected void updateItem(FeedItem item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null || item.getMedia() == null) {
                    setText(null);
                    setGraphic(null);
                    return;
                }
                FeedMedia media = item.getMedia();
                Label title = new Label(item.getTitle());
                title.setWrapText(true);
                StringBuilder meta = new StringBuilder();
                String feedTitle = feedTitleOf(item.getFeedId());
                if (feedTitle != null) {
                    meta.append(feedTitle);
                }
                boolean running = downloader.isDownloading(media.getId());
                if (running) {
                    Integer percent = downloadProgress.get(media.getId());
                    appendMeta(meta, percent != null && percent >= 0
                            ? Messages.format("downloads.meta.progress", percent)
                            : Messages.get("downloads.meta.downloading"));
                } else {
                    if (media.getSize() > 0) {
                        appendMeta(meta, formatSize(media.getSize()));
                    }
                    if (item.isPlayed()) {
                        appendMeta(meta, Messages.get("episodes.meta.played"));
                    }
                }
                Label metaLabel = new Label(meta.toString());
                metaLabel.getStyleClass().add("muted-label");
                VBox texts = new VBox(2, title, metaLabel);
                texts.setMinWidth(0);
                HBox.setHgrow(texts, Priority.ALWAYS);
                HBox row;
                if (running) {
                    Button cancel = iconButton(Icons.stop(), Messages.get("downloads.cancel"));
                    cancel.setOnAction(event -> downloader.cancel(media.getId()));
                    row = new HBox(8, texts, cancel);
                } else {
                    Button play = iconButton(Icons.play(), Messages.get("common.play"));
                    play.setOnAction(event -> playback.play(item, new ArrayList<>(items)));
                    Button delete = iconButton(Icons.remove(), Messages.get("downloads.delete"));
                    delete.setOnAction(event -> deleteDownloads(List.of(item), afterDelete));
                    row = new HBox(8, texts, play, delete);
                }
                row.setAlignment(Pos.CENTER_LEFT);
                setGraphic(row);
                setText(null);
            }
        }));
        list.setOnMouseClicked(event -> {
            if (event.getClickCount() == 2) {
                FeedItem selected = list.getSelectionModel().getSelectedItem();
                if (selected != null && selected.getMedia() != null
                        && !downloader.isDownloading(selected.getMedia().getId())) {
                    playback.play(selected, new ArrayList<>(items));
                }
            }
        });
        Button deletePlayed = new Button(Messages.get("downloads.delete_played"), Icons.remove());
        deletePlayed.setTooltip(new Tooltip(Messages.get("downloads.delete_played.tooltip")));
        deletePlayed.setOnAction(event -> {
            List<FeedItem> played = new ArrayList<>();
            for (FeedItem item : items) {
                if (item.isPlayed() && item.getMedia() != null && item.getMedia().localFileAvailable()) {
                    played.add(item);
                }
            }
            if (played.isEmpty()) {
                setStatus(Messages.get("status.downloads.no_played"));
                return;
            }
            deleteDownloads(played, afterDelete);
        });
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox top = new HBox(8, summary, spacer, deletePlayed);
        top.setAlignment(Pos.CENTER_LEFT);
        VBox pane = new VBox(8, top, list);
        pane.setPadding(new Insets(8));
        VBox.setVgrow(list, Priority.ALWAYS);
        showSidebar(Messages.get("downloads.title"), pane);
        startDownloadsTicker(list, reload);
    }

    /**
     * Keeps the open Downloads view current: percentages redraw every second, and a transfer
     * starting or ending reloads the rows. Polled rather than pushed because auto-downloads report
     * to their own listener; it stops once the sidebar shows something else.
     */
    private void startDownloadsTicker(ListView<FeedItem> list, Runnable reload) {
        if (downloadsTicker != null) {
            downloadsTicker.stop();
        }
        List<Long> lastActive = new ArrayList<>(downloader.activeMediaIds());
        downloadsTicker = new javafx.animation.Timeline(new javafx.animation.KeyFrame(
                Duration.seconds(1), event -> {
                    if (!sidebar.isVisible() || !Messages.get("downloads.title").equals(sidebarTitle.getText())
                            || !sidebarContent.getChildren().contains(list.getParent())) {
                        downloadsTicker.stop();
                        return;
                    }
                    List<Long> active = downloader.activeMediaIds();
                    if (!new java.util.HashSet<>(active).equals(new java.util.HashSet<>(lastActive))) {
                        lastActive.clear();
                        lastActive.addAll(active);
                        reload.run();
                    } else if (!active.isEmpty()) {
                        list.refresh();
                    }
                }));
        downloadsTicker.setCycleCount(javafx.animation.Animation.INDEFINITE);
        downloadsTicker.play();
    }

    private static void appendMeta(StringBuilder meta, String part) {
        if (meta.length() > 0) {
            meta.append(" · ");
        }
        meta.append(part);
    }

    private String feedTitleOf(long feedId) {
        for (Feed feed : feeds) {
            if (feed.getId() == feedId) {
                return feed.getTitle() != null ? feed.getTitle() : feed.getDownloadUrl();
            }
        }
        return null;
    }

    private void showFeedSettings(Feed feed) {
        background.submit(() -> {
            try {
                FeedPrefs prefs = database.getFeedPrefs(feed.getId());
                FeedCredentials.Login login = database.getFeedCredentials(feed.getId());
                Platform.runLater(() -> showFeedSettingsDialog(feed, prefs, login));
            } catch (Exception e) {
                setStatus(Messages.format("status.feed_settings.load_failed", e.getMessage()));
            }
        });
    }

    private void showFeedSettingsDialog(Feed feed, FeedPrefs prefs, FeedCredentials.Login login) {
        javafx.scene.layout.GridPane grid = new javafx.scene.layout.GridPane();
        grid.setHgap(8);
        grid.setVgap(8);
        grid.setPadding(new Insets(12));
        int row = 0;
        grid.add(new Label(Messages.get("feed_settings.speed")), 0, row);
        Slider speedSlider = new Slider(0, 2.5, prefs.speed);
        speedSlider.setShowTickLabels(true);
        speedSlider.setShowTickMarks(true);
        speedSlider.setMajorTickUnit(0.5);
        Label speedValue = new Label(speedLabel(prefs.speed));
        speedSlider.valueProperty().addListener((obs, oldValue, newValue) ->
                speedValue.setText(speedLabel(newValue.floatValue())));
        grid.add(new HBox(8, speedSlider, speedValue), 1, row++);
        grid.add(new Label(Messages.get("feed_settings.auto_download")), 0, row);
        ComboBox<String> downloadBox = triStateBox(prefs.autoDownload);
        grid.add(downloadBox, 1, row++);
        grid.add(new Label(Messages.get("feed_settings.auto_delete")), 0, row);
        ComboBox<String> deleteBox = triStateBox(prefs.autoDelete);
        grid.add(deleteBox, 1, row++);
        grid.add(new Label(Messages.get("feed_settings.include_filter")), 0, row);
        TextField includeField = new TextField(prefs.includeFilter);
        grid.add(includeField, 1, row++);
        grid.add(new Label(Messages.get("feed_settings.exclude_filter")), 0, row);
        TextField excludeField = new TextField(prefs.excludeFilter);
        grid.add(excludeField, 1, row++);
        grid.add(new Label(Messages.get("feed_settings.min_duration")), 0, row);
        TextField minDurationField = new TextField(
                prefs.minDurationSec < 0 ? "-1" : String.valueOf(prefs.minDurationSec / 60));
        grid.add(minDurationField, 1, row++);
        grid.add(new Label(Messages.get("feed_settings.sort")), 0, row);
        ComboBox<String> sortBox = new ComboBox<>();
        sortBox.getItems().addAll(Messages.get("sort.newest"), Messages.get("sort.oldest"),
                Messages.get("sort.shortest"),
                Messages.get("sort.longest"), Messages.get("sort.title"));
        sortBox.setValue(sortLabel(prefs.sortCode));
        grid.add(sortBox, 1, row++);
        // the controls are read here, on the FX thread; the write goes through prefsWriter
        Runnable save = () -> {
            float speed = (float) Math.round(speedSlider.getValue() * 20) / 20f;
            int autoDownload = triStateValue(downloadBox.getValue());
            int autoDelete = triStateValue(deleteBox.getValue());
            String include = includeField.getText().trim();
            String exclude = excludeField.getText().trim();
            int minDurationSec;
            try {
                int minutes = Integer.parseInt(minDurationField.getText().trim());
                minDurationSec = minutes < 0 ? -1 : minutes * 60;
            } catch (NumberFormatException e) {
                minDurationSec = -1;
            }
            int minDuration = minDurationSec;
            prefsWriter.submit(() -> {
                try {
                    FeedPrefs stored = database.getFeedPrefs(feed.getId());
                    stored.speed = speed;
                    stored.autoDownload = autoDownload;
                    stored.autoDelete = autoDelete;
                    stored.includeFilter = include;
                    stored.excludeFilter = exclude;
                    stored.minDurationSec = minDuration;
                    database.saveFeedPrefs(stored);
                    setStatus(Messages.get("status.feed_settings.saved"));
                    Platform.runLater(() -> {
                        if (selectedFeed != null && selectedFeed.getId() == feed.getId()) {
                            loadEpisodes(feed);
                        }
                    });
                } catch (Exception e) {
                    setStatus(Messages.format("status.feed_settings.save_failed", e.getMessage()));
                }
            });
        };
        autoSave(speedSlider, save);
        autoSave(downloadBox.valueProperty(), save);
        autoSave(deleteBox.valueProperty(), save);
        autoSave(includeField, save);
        autoSave(excludeField, save);
        autoSave(minDurationField, save);
        // the order is saved on its own, the same way the episode pane's sort box saves it
        autoSave(sortBox.valueProperty(), () -> saveSortCode(feed, sortCode(sortBox.getValue())));
        Label savedHint = new Label(Messages.get("common.saved_automatically"));
        savedHint.setWrapText(true);
        grid.add(savedHint, 0, row++, 2, 1);
        // the login is saved on request, not per keystroke, and is tried out right away
        grid.add(sectionLabel(Messages.get("feed_settings.login_section")), 0, row++, 2, 1);
        grid.add(new Label(Messages.get("common.username_label")), 0, row);
        TextField userField = new TextField(login != null ? login.username : "");
        grid.add(userField, 1, row++);
        grid.add(new Label(Messages.get("common.password_label")), 0, row);
        javafx.scene.control.PasswordField passField = new javafx.scene.control.PasswordField();
        passField.setText(login != null ? login.password : "");
        grid.add(passField, 1, row++);
        Button saveLogin = new Button(Messages.get("feed_settings.save_login"));
        saveLogin.setOnAction(event -> {
            String user = userField.getText().trim();
            FeedCredentials.Login entered = user.isEmpty()
                    ? null : new FeedCredentials.Login(user, passField.getText());
            spinWhile(saveLogin, () -> {
                try {
                    feedUpdater.setCredentials(feed.getId(), entered);
                    if (entered == null) {
                        setStatus(Messages.format("status.login.removed", feed.getTitle()));
                        return;
                    }
                    setStatus(Messages.format("status.login.saved", feed.getTitle()));
                    doRefreshFeed(feed);
                } catch (Exception e) {
                    setStatus(Messages.format("status.login.save_failed", e.getMessage()));
                }
            });
        });
        Label loginHint = new Label(Messages.get("feed_settings.login_hint"));
        loginHint.getStyleClass().add("muted-label");
        loginHint.setWrapText(true);
        grid.add(new HBox(8, saveLogin), 1, row++);
        grid.add(loginHint, 1, row++);
        showSidebar(Messages.format("feed_settings.title", feed.getTitle()), new VBox(grid));
    }

    private static String speedLabel(float speed) {
        return speed <= 0 ? Messages.get("feed_settings.speed.global") : String.format(Locale.US, "%.2fx", speed);
    }

    /** The one way silence skipping is switched: player bar, tray or taskbar thumbnail. */
    private void setSilenceSkipping(boolean enabled) {
        playback.setSilenceSkipping(enabled);
        updateSilenceButtonTooltip();
        if (trayActive) {
            trayManager.updateSilenceSkipping(enabled);
        }
        windowsTaskbar.setSilenceSkipping(enabled);
        setStatus(enabled ? Messages.get("status.skip_silence.enabled") : Messages.get("status.skip_silence.disabled"));
    }

    private void updateSilenceButtonTooltip() {
        if (silenceButton != null) {
            boolean enabled = DesktopPreferences.getSkipSilence();
            silenceButton.setTooltip(new Tooltip(enabled
                    ? Messages.get("player.skip_silence.on") : Messages.get("player.skip_silence.off")));
            silenceButton.setOpacity(enabled ? 1.0 : 0.55);
        }
    }

    private static final String[] SPEED_OPTIONS =
            {"0.5x", "0.75x", "1.0x", "1.25x", "1.5x", "1.75x", "2.0x", "2.5x", "3.0x"};

    private static String closestSpeed(float speed) {
        String closest = "1.0x";
        float bestDiff = Float.MAX_VALUE;
        for (String option : SPEED_OPTIONS) {
            float diff = Math.abs(Float.parseFloat(option.replace("x", "")) - speed);
            if (diff < bestDiff) {
                bestDiff = diff;
                closest = option;
            }
        }
        return closest;
    }

    private static final String THEME_LABEL_AUTO = Messages.get("settings.theme.auto");
    private static final String THEME_LABEL_LIGHT = Messages.get("settings.theme.light");
    private static final String THEME_LABEL_DARK = Messages.get("settings.theme.dark");
    private static final String[] THEME_OPTIONS =
            {THEME_LABEL_AUTO, THEME_LABEL_LIGHT, THEME_LABEL_DARK};

    private static String themeModeLabel(String mode) {
        if (ThemeManager.MODE_LIGHT.equals(mode)) {
            return THEME_LABEL_LIGHT;
        }
        if (ThemeManager.MODE_DARK.equals(mode)) {
            return THEME_LABEL_DARK;
        }
        return THEME_LABEL_AUTO;
    }

    private static String themeModeValue(String label) {
        if (THEME_LABEL_LIGHT.equals(label)) {
            return ThemeManager.MODE_LIGHT;
        }
        if (THEME_LABEL_DARK.equals(label)) {
            return ThemeManager.MODE_DARK;
        }
        return ThemeManager.MODE_AUTO;
    }

    private static ComboBox<String> triStateBox(int value) {
        ComboBox<String> box = new ComboBox<>();
        box.getItems().addAll(Messages.get("feed_settings.tristate.global"),
                Messages.get("feed_settings.tristate.on"), Messages.get("feed_settings.tristate.off"));
        box.setValue(value == FeedPrefs.ON ? Messages.get("feed_settings.tristate.on")
                : (value == FeedPrefs.OFF ? Messages.get("feed_settings.tristate.off")
                        : Messages.get("feed_settings.tristate.global")));
        return box;
    }

    private static int triStateValue(String value) {
        return Messages.get("feed_settings.tristate.on").equals(value) ? FeedPrefs.ON
                : (Messages.get("feed_settings.tristate.off").equals(value) ? FeedPrefs.OFF : FeedPrefs.USE_GLOBAL);
    }

    private static String sortLabel(String code) {
        if (code == null) {
            return Messages.get("sort.newest");
        }
        switch (code) {
            case "oldest": return Messages.get("sort.oldest");
            case "shortest": return Messages.get("sort.shortest");
            case "longest": return Messages.get("sort.longest");
            case "title": return Messages.get("sort.title");
            default: return Messages.get("sort.newest");
        }
    }

    private static String sortCode(String label) {
        // the labels come from the bundle, so they cannot be switch cases; equals on the label
        // keeps the old switch's NullPointerException for a null label
        if (label.equals(Messages.get("sort.oldest"))) {
            return "oldest";
        }
        if (label.equals(Messages.get("sort.shortest"))) {
            return "shortest";
        }
        if (label.equals(Messages.get("sort.longest"))) {
            return "longest";
        }
        if (label.equals(Messages.get("sort.title"))) {
            return "title";
        }
        return "newest";
    }

    private void showHistory() {
        background.submit(() -> {
            try {
                List<FeedItem> history = database.getPlaybackHistory(200);
                Platform.runLater(() -> {
                    ObservableList<FeedItem> items = FXCollections.observableArrayList(history);
                    ListView<FeedItem> list = new ListView<>(items);
                    SimpleDateFormat dateFormat = new SimpleDateFormat("d MMM yyyy HH:mm", Locale.US);
                    list.setCellFactory(view -> new ListCell<>() {
                        @Override
                        protected void updateItem(FeedItem item, boolean empty) {
                            super.updateItem(item, empty);
                            if (empty || item == null) {
                                setText(null);
                                return;
                            }
                            String date = "";
                            if (item.getMedia() != null
                                    && item.getMedia().getLastPlayedTimeHistory() != null) {
                                date = dateFormat.format(item.getMedia().getLastPlayedTimeHistory()) + " · ";
                            }
                            setText(date + item.getTitle());
                        }
                    });
                    list.setOnMouseClicked(event -> {
                        if (event.getClickCount() == 2) {
                            FeedItem selected = list.getSelectionModel().getSelectedItem();
                            if (selected != null && selected.getMedia() != null) {
                                playback.play(selected, new ArrayList<>(items));
                            }
                        }
                    });
                    Button clearButton = new Button(Messages.get("history.clear"));
                    clearButton.setOnAction(event -> background.submit(() -> {
                        try {
                            database.clearPlaybackHistory();
                            Platform.runLater(items::clear);
                        } catch (Exception e) {
                            setStatus(Messages.format("status.history.clear_failed", e.getMessage()));
                        }
                    }));
                    VBox pane = new VBox(8, list, clearButton);
                    pane.setPadding(new Insets(8));
                    VBox.setVgrow(list, Priority.ALWAYS);
                    showSidebar(Messages.get("history.title"), pane);
                });
            } catch (Exception e) {
                setStatus(Messages.format("status.history.load_failed", e.getMessage()));
            }
        });
    }

    private void showStatistics() {
        background.submit(() -> {
            try {
                List<DesktopDatabase.FeedStatistics> feeds = database.getFeedStatistics();
                List<DesktopDatabase.MonthlyStatistics> months = database.getMonthlyStatistics();
                List<String> lines = new ArrayList<>();
                long totalPlayed = 0;
                long totalTime = 0;
                int totalEpisodes = 0;
                int totalDownloaded = 0;
                long totalSize = 0;
                for (DesktopDatabase.FeedStatistics row : feeds) {
                    totalPlayed += row.playedTimeMs;
                    totalTime += row.totalTimeMs;
                    totalEpisodes += row.episodes;
                    totalDownloaded += row.downloaded;
                    totalSize += row.downloadSizeBytes;
                }
                lines.add(Messages.format("stats.listened", formatDuration(totalPlayed),
                        formatDuration(totalTime)));
                lines.add(Messages.format("stats.totals", totalEpisodes, totalDownloaded,
                        formatSize(totalSize)));
                lines.add("");
                lines.add(Messages.get("stats.per_podcast"));
                for (DesktopDatabase.FeedStatistics row : feeds) {
                    lines.add(Messages.format("stats.podcast_row",
                            row.feedTitle != null ? row.feedTitle : "?",
                            formatDuration(row.playedTimeMs), row.episodes, row.unplayed));
                }
                if (!months.isEmpty()) {
                    lines.add("");
                    lines.add(Messages.get("stats.by_month"));
                    for (DesktopDatabase.MonthlyStatistics month : months) {
                        lines.add(month.month + ": " + formatDuration(month.playedTimeMs));
                    }
                }
                Platform.runLater(() -> {
                    ListView<String> list = new ListView<>(FXCollections.observableArrayList(lines));
                    VBox pane = new VBox(8, list);
                    pane.setPadding(new Insets(8));
                    showSidebar(Messages.get("stats.title"), pane);
                });
            } catch (Exception e) {
                setStatus(Messages.format("status.stats.load_failed", e.getMessage()));
            }
        });
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format(Locale.US, "%.1f KB", bytes / 1024.0);
        }
        if (bytes < 1024L * 1024 * 1024) {
            return String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024));
        }
        return String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }

    private void showSettings() {
        TabPane tabs = new TabPane();
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);

        // ---- General: appearance, window behaviour, updates ---------------
        javafx.scene.layout.GridPane grid = settingsGrid();
        int row = 0;
        grid.add(sectionLabel(Messages.get("settings.section.appearance")), 0, row++, 2, 1);
        grid.add(new Label(Messages.get("settings.theme")), 0, row);
        ComboBox<String> themeBox = new ComboBox<>();
        themeBox.getItems().addAll(THEME_OPTIONS);
        themeBox.setValue(themeModeLabel(DesktopPreferences.getThemeMode()));
        grid.add(themeBox, 1, row++);
        grid.add(sectionLabel(Messages.get("settings.section.window")), 0, row++, 2, 1);
        javafx.scene.control.CheckBox closeToTrayBox = new javafx.scene.control.CheckBox(
                Messages.get("settings.close_to_tray"));
        closeToTrayBox.setSelected(DesktopPreferences.getCloseToTray());
        grid.add(closeToTrayBox, 0, row++, 2, 1);
        javafx.scene.control.CheckBox mediaKeysBox = new javafx.scene.control.CheckBox(
                Messages.get("settings.media_keys"));
        mediaKeysBox.setSelected(DesktopPreferences.getMediaKeysEnabled());
        mediaKeysBox.setDisable(!MediaKeys.isEnabled());
        mediaKeysBox.setTooltip(new Tooltip(Messages.get("settings.media_keys.tooltip")));
        grid.add(mediaKeysBox, 0, row++, 2, 1);
        javafx.scene.control.CheckBox withWindowsBox = new javafx.scene.control.CheckBox(
                Messages.get("settings.start_with_windows"));
        withWindowsBox.setSelected(StartupRegistration.isEnabled());
        withWindowsBox.setDisable(!StartupRegistration.isAvailable());
        withWindowsBox.setTooltip(new Tooltip(StartupRegistration.isAvailable()
                ? Messages.get("settings.start_with_windows.tooltip")
                : Messages.get("settings.start_with_windows.unavailable")));
        withWindowsBox.selectedProperty().addListener((obs, was, on) -> {
            if (!StartupRegistration.setEnabled(on)) {
                setStatus(Messages.get("status.startup.change_failed"));
                withWindowsBox.setSelected(StartupRegistration.isEnabled());
            } else {
                setStatus(on ? Messages.get("status.startup.enabled")
                        : Messages.get("status.startup.disabled"));
            }
        });
        grid.add(withWindowsBox, 0, row++, 2, 1);
        javafx.scene.control.CheckBox notifyBox = new javafx.scene.control.CheckBox(
                Messages.get("settings.notify"));
        notifyBox.setSelected(DesktopPreferences.getNotifyNewEpisodes());
        notifyBox.setTooltip(new Tooltip(Messages.get("settings.notify.tooltip")));
        notifyBox.selectedProperty().addListener((obs, was, on) -> DesktopPreferences.setNotifyNewEpisodes(on));
        grid.add(notifyBox, 0, row++, 2, 1);
        grid.add(sectionLabel(Messages.get("settings.section.updates")), 0, row++, 2, 1);
        Label versionLabel = new Label(Messages.format("settings.version", appVersion()));
        versionLabel.getStyleClass().add("muted-label");
        grid.add(versionLabel, 0, row++, 2, 1);
        javafx.scene.control.CheckBox updateCheckBox =
                new javafx.scene.control.CheckBox(Messages.get("settings.update_check"));
        updateCheckBox.setSelected(DesktopPreferences.getUpdateCheckEnabled());
        grid.add(updateCheckBox, 0, row++, 2, 1);
        Button checkUpdatesButton = new Button(Messages.get("settings.check_updates"));
        checkUpdatesButton.setOnAction(event -> {
            // a check the user asked for reports whatever it finds, and offers a skipped release
            // again, because asking for it is the point
            DesktopPreferences.setSkippedUpdateVersion("");
            background.submit(() -> checkForUpdates(true));
        });
        grid.add(checkUpdatesButton, 0, row++, 2, 1);
        grid.add(sectionLabel(Messages.get("settings.section.data")), 0, row++, 2, 1);
        File dataDir = DesktopPreferences.getDataDir();
        boolean portable = DesktopPreferences.getPortableDir() != null;
        Label dataLabel = new Label(portable
                ? Messages.format("settings.data.portable", dataDir.getAbsolutePath())
                : Messages.format("settings.data.installed", dataDir.getAbsolutePath(),
                        DesktopPreferences.PORTABLE_FOLDER));
        dataLabel.getStyleClass().add("muted-label");
        dataLabel.setWrapText(true);
        grid.add(dataLabel, 0, row++, 2, 1);
        Button openDataButton = new Button(Messages.get("settings.open_data_folder"), Icons.folder());
        openDataButton.setOnAction(event -> getHostServices().showDocument(dataDir.toURI().toString()));
        Button backupButton = new Button(Messages.get("settings.backup"), Icons.upload());
        backupButton.setTooltip(new Tooltip(Messages.get("settings.backup.tooltip")));
        backupButton.setOnAction(event -> backUpProfile(backupButton));
        Button restoreButton = new Button(Messages.get("settings.restore"), Icons.download());
        restoreButton.setTooltip(new Tooltip(Messages.get("settings.restore.tooltip")));
        restoreButton.setOnAction(event -> restoreProfile(restoreButton));
        grid.add(new HBox(8, backupButton, restoreButton, openDataButton), 0, row++, 2, 1);
        Label backupHint = new Label(Messages.get("settings.backup.hint"));
        backupHint.getStyleClass().add("muted-label");
        backupHint.setWrapText(true);
        grid.add(backupHint, 0, row++, 2, 1);
        tabs.getTabs().add(settingsTab(Messages.get("settings.tab.general"), grid));

        // ---- Playback ------------------------------------------------------
        grid = settingsGrid();
        row = 0;
        grid.add(sectionLabel(Messages.get("settings.section.playback")), 0, row++, 2, 1);
        grid.add(new Label(Messages.get("settings.default_speed")), 0, row);
        ComboBox<String> settingsSpeedBox = new ComboBox<>();
        settingsSpeedBox.getItems().addAll(SPEED_OPTIONS);
        settingsSpeedBox.setValue(closestSpeed(DesktopPreferences.getPlaybackSpeed()));
        grid.add(settingsSpeedBox, 1, row++);
        grid.add(new Label(Messages.get("settings.skip_intro")), 0, row);
        TextField introField = new TextField(String.valueOf(DesktopPreferences.getSkipIntroSec()));
        grid.add(introField, 1, row++);
        grid.add(new Label(Messages.get("settings.skip_ending")), 0, row);
        TextField endingField = new TextField(String.valueOf(DesktopPreferences.getSkipEndingSec()));
        grid.add(endingField, 1, row++);
        grid.add(new Label(Messages.get("settings.skip_back")), 0, row);
        TextField skipBackField = new TextField(String.valueOf(DesktopPreferences.getSkipBackSec()));
        grid.add(skipBackField, 1, row++);
        grid.add(new Label(Messages.get("settings.skip_forward")), 0, row);
        TextField skipForwardField =
                new TextField(String.valueOf(DesktopPreferences.getSkipForwardSec()));
        grid.add(skipForwardField, 1, row++);
        grid.add(new Label(Messages.get("settings.volume_boost")), 0, row);
        Slider boostSlider = new Slider(0, 12, DesktopPreferences.getVolumeBoostDb());
        boostSlider.setShowTickLabels(true);
        boostSlider.setMajorTickUnit(3);
        boostSlider.setSnapToTicks(true);
        grid.add(boostSlider, 1, row++);
        tabs.getTabs().add(settingsTab(Messages.get("settings.tab.playback"), grid));

        // ---- Downloads: download defaults and the episode cache ------------
        grid = settingsGrid();
        row = 0;
        grid.add(sectionLabel(Messages.get("settings.section.downloads")), 0, row++, 2, 1);
        javafx.scene.control.CheckBox downloadBox =
                new javafx.scene.control.CheckBox(Messages.get("settings.auto_download_default"));
        downloadBox.setSelected(DesktopPreferences.getAutoDownloadDefault());
        grid.add(downloadBox, 0, row++, 2, 1);
        javafx.scene.control.CheckBox deleteBox =
                new javafx.scene.control.CheckBox(Messages.get("settings.auto_delete_default"));
        deleteBox.setSelected(DesktopPreferences.getAutoDeleteDefault());
        grid.add(deleteBox, 0, row++, 2, 1);
        Button openMediaButton = new Button(Messages.get("settings.open_media_folder"));
        openMediaButton.setOnAction(event ->
                getHostServices().showDocument(DesktopPreferences.getMediaDir().toURI().toString()));
        grid.add(openMediaButton, 0, row++, 2, 1);
        grid.add(sectionLabel(Messages.get("settings.section.cache")), 0, row++, 2, 1);
        javafx.scene.control.CheckBox cacheBox =
                new javafx.scene.control.CheckBox(Messages.get("settings.cache.enabled"));
        cacheBox.setSelected(DesktopPreferences.getEpisodeCacheEnabled());
        grid.add(cacheBox, 0, row++, 2, 1);
        javafx.scene.control.CheckBox cacheFinishBox =
                new javafx.scene.control.CheckBox(Messages.get("settings.cache.remove_finished"));
        cacheFinishBox.setSelected(DesktopPreferences.getEpisodeCacheRemoveAfterFinish());
        grid.add(cacheFinishBox, 0, row++, 2, 1);
        grid.add(new Label(Messages.get("settings.cache.limit")), 0, row);
        TextField cacheLimitField = new TextField(String.valueOf(DesktopPreferences.getEpisodeCacheLimitMb()));
        grid.add(cacheLimitField, 1, row++);
        grid.add(new Label(Messages.get("settings.cache.prefetch")), 0, row);
        TextField cachePrefetchField =
                new TextField(String.valueOf(DesktopPreferences.getEpisodeCachePrefetchCount()));
        grid.add(cachePrefetchField, 1, row++);
        Label cacheUsageLabel = new Label(Messages.get("settings.cache.checking"));
        grid.add(cacheUsageLabel, 0, row++, 2, 1);
        Runnable refreshCacheUsage = () -> background.submit(() -> {
            long used = episodeCache.sizeBytes();
            int limitMb = DesktopPreferences.getEpisodeCacheLimitMb();
            String limitText = limitMb > 0 ? formatSize(limitMb * 1024L * 1024L) : Messages.get("settings.cache.no_limit");
            String usage = Messages.format("settings.cache.usage", formatSize(used), limitText,
                    episodeCache.count());
            Platform.runLater(() -> cacheUsageLabel.setText(usage));
        });
        refreshCacheUsage.run();
        Button clearCacheButton = new Button(Messages.get("settings.cache.clear"));
        clearCacheButton.setOnAction(event -> background.submit(() -> {
            int removed = episodeCache.clear();
            setStatus(Messages.format("status.cache.cleared", removed == 1
                    ? Messages.get("count.episode.one") : Messages.format("count.episode.other", removed)));
            refreshCacheUsage.run();
            Platform.runLater(episodeList::refresh);
        }));
        grid.add(clearCacheButton, 0, row++, 2, 1);
        Button openCacheButton = new Button(Messages.get("settings.cache.open_folder"));
        openCacheButton.setOnAction(event ->
                getHostServices().showDocument(DesktopPreferences.getEpisodeCacheDir().toURI().toString()));
        grid.add(openCacheButton, 0, row++, 2, 1);
        tabs.getTabs().add(settingsTab(Messages.get("settings.tab.downloads"), grid));

        // ---- Network: refresh schedule and proxy ----------------------------
        grid = settingsGrid();
        row = 0;
        grid.add(sectionLabel(Messages.get("settings.section.refresh")), 0, row++, 2, 1);
        javafx.scene.control.CheckBox startupBox =
                new javafx.scene.control.CheckBox(Messages.get("settings.refresh_startup"));
        startupBox.setSelected(DesktopPreferences.getAutoRefreshStartup());
        grid.add(startupBox, 0, row++, 2, 1);
        grid.add(new Label(Messages.get("settings.refresh_interval")), 0, row);
        TextField intervalField = new TextField(String.valueOf(DesktopPreferences.getAutoRefreshMinutes()));
        grid.add(intervalField, 1, row++);
        grid.add(sectionLabel(Messages.get("settings.section.proxy")), 0, row++, 2, 1);
        grid.add(new Label(Messages.get("common.host_label")), 0, row);
        TextField proxyHost = new TextField(DesktopPreferences.getProxyHost());
        grid.add(proxyHost, 1, row++);
        grid.add(new Label(Messages.get("settings.proxy_port")), 0, row);
        TextField proxyPort = new TextField(String.valueOf(DesktopPreferences.getProxyPort()));
        grid.add(proxyPort, 1, row++);
        grid.add(new Label(Messages.get("common.username_label")), 0, row);
        TextField proxyUser = new TextField(DesktopPreferences.getProxyUser());
        grid.add(proxyUser, 1, row++);
        grid.add(new Label(Messages.get("common.password_label")), 0, row);
        javafx.scene.control.PasswordField proxyPass = new javafx.scene.control.PasswordField();
        proxyPass.setText(DesktopPreferences.getProxyPassword());
        grid.add(proxyPass, 1, row++);
        tabs.getTabs().add(settingsTab(Messages.get("settings.tab.network"), grid));
        tabs.getTabs().add(buildLogsTab());

        Label savedLabel = new Label(Messages.get("common.saved_automatically"));
        savedLabel.setWrapText(true);
        Runnable save = () -> {
            try {
                DesktopPreferences.setPlaybackSpeed(
                        Float.parseFloat(settingsSpeedBox.getValue().replace("x", "")));
                DesktopPreferences.setSkipIntroSec(parseNonNegative(introField.getText()));
                DesktopPreferences.setSkipEndingSec(parseNonNegative(endingField.getText()));
                DesktopPreferences.setSkipBackSec(parseNonNegative(skipBackField.getText()));
                DesktopPreferences.setSkipForwardSec(parseNonNegative(skipForwardField.getText()));
                updateSkipTooltips();
                DesktopPreferences.setVolumeBoostDb((int) boostSlider.getValue());
                DesktopPreferences.setAutoDownloadDefault(downloadBox.isSelected());
                DesktopPreferences.setAutoDeleteDefault(deleteBox.isSelected());
                DesktopPreferences.setEpisodeCacheEnabled(cacheBox.isSelected());
                DesktopPreferences.setEpisodeCacheRemoveAfterFinish(cacheFinishBox.isSelected());
                DesktopPreferences.setEpisodeCacheLimitMb(parseNonNegative(cacheLimitField.getText()));
                DesktopPreferences.setEpisodeCachePrefetchCount(parseNonNegative(cachePrefetchField.getText()));
                DesktopPreferences.setUpdateCheckEnabled(updateCheckBox.isSelected());
                background.submit(episodeCache::trim);
                DesktopPreferences.setAutoRefreshStartup(startupBox.isSelected());
                DesktopPreferences.setAutoRefreshMinutes(parseNonNegative(intervalField.getText()));
                DesktopPreferences.setProxyHost(proxyHost.getText().trim());
                try {
                    DesktopPreferences.setProxyPort(Integer.parseInt(proxyPort.getText().trim()));
                } catch (NumberFormatException e) {
                    DesktopPreferences.setProxyPort(8080);
                }
                DesktopPreferences.setProxyUser(proxyUser.getText().trim());
                DesktopPreferences.setProxyPassword(proxyPass.getText());
                DesktopPreferences.setThemeMode(themeModeValue(themeBox.getValue()));
                DesktopPreferences.setCloseToTray(closeToTrayBox.isSelected());
                setMediaKeysEnabled(mediaKeysBox.isSelected());
                applyProxy();
                scheduleAutoRefresh();
                ThemeManager.applySavedMode();
                paintVolumeTrack();
                savedLabel.setText(
                        Messages.get("settings.saved_note"));
                setStatus(Messages.get("status.settings.saved"));
            } catch (Exception e) {
                savedLabel.setText(Messages.format("settings.save_failed", e.getMessage()));
            }
        };
        autoSave(themeBox.valueProperty(), save);
        autoSave(settingsSpeedBox.valueProperty(), save);
        autoSave(introField, save);
        autoSave(endingField, save);
        autoSave(skipBackField, save);
        autoSave(skipForwardField, save);
        autoSave(boostSlider, save);
        autoSave(downloadBox.selectedProperty(), save);
        autoSave(deleteBox.selectedProperty(), save);
        autoSave(cacheBox.selectedProperty(), save);
        autoSave(cacheFinishBox.selectedProperty(), save);
        autoSave(cacheLimitField, save);
        autoSave(cachePrefetchField, save);
        autoSave(updateCheckBox.selectedProperty(), save);
        autoSave(startupBox.selectedProperty(), save);
        autoSave(intervalField, save);
        autoSave(proxyHost, save);
        autoSave(proxyPort, save);
        autoSave(proxyUser, save);
        autoSave(proxyPass, save);
        autoSave(closeToTrayBox.selectedProperty(), save);
        autoSave(mediaKeysBox.selectedProperty(), save);

        VBox.setVgrow(tabs, Priority.ALWAYS);
        HBox savedBar = new HBox(savedLabel);
        savedBar.setPadding(new Insets(6, 12, 10, 12));
        showModal(Messages.get("settings.title"), new VBox(tabs, savedBar), true);
    }

    /** A fresh grid for one settings tab. */
    private static javafx.scene.layout.GridPane settingsGrid() {
        javafx.scene.layout.GridPane grid = new javafx.scene.layout.GridPane();
        grid.setHgap(8);
        grid.setVgap(8);
        grid.setPadding(new Insets(12));
        return grid;
    }

    /** Copies text to the system clipboard for pasting into bug reports. */
    private static void copyText(String text) {
        ClipboardContent content = new ClipboardContent();
        content.putString(text != null ? text : "");
        Clipboard.getSystemClipboard().setContent(content);
    }

    /** The Settings logs tab: the status history, newest first, easy to copy out. */
    private Tab buildLogsTab() {
        List<String> lines = new ArrayList<>();
        java.text.SimpleDateFormat stamp = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        synchronized (statusLog) {
            for (StatusEntry entry : statusLog) {
                lines.add(stamp.format(new Date(entry.timeMs)) + "  " + entry.text);
            }
        }
        ObservableList<String> items = FXCollections.observableArrayList(lines);
        ListView<String> list = new ListView<>(items);
        list.setOnMouseClicked(event -> {
            if (event.getClickCount() == 2) {
                String selected = list.getSelectionModel().getSelectedItem();
                if (selected != null) {
                    copyText(selected);
                    setStatus(Messages.get("status.log.line_copied"));
                }
            }
        });
        Label hint = new Label(Messages.get("settings.logs.hint"));
        hint.getStyleClass().add("muted-label");
        Button copyAll = new Button(Messages.get("settings.logs.copy_all"));
        copyAll.setOnAction(event -> {
            copyText(String.join("\n", items));
            setStatus(Messages.format("status.log.copied", items.size()));
        });
        HBox header = new HBox(8, hint, copyAll);
        header.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(hint, Priority.ALWAYS);
        VBox pane = new VBox(8, header, list);
        pane.setPadding(new Insets(12));
        VBox.setVgrow(list, Priority.ALWAYS);
        Tab tab = new Tab(Messages.get("settings.tab.logs"), pane);
        tab.setClosable(false);
        return tab;
    }

    /** Wraps one settings grid in a scrolling, fixed (non-closable) tab. */
    private static Tab settingsTab(String title, javafx.scene.layout.GridPane grid) {
        ScrollPane scroll = new ScrollPane(grid);
        scroll.setFitToWidth(true);
        Tab tab = new Tab(title, withScrollHints(scroll));
        tab.setClosable(false);
        return tab;
    }

    /**
     * Shows that a scroll pane holds more than fits: a fade along the edge with more beyond it,
     * and a "More" pill at the bottom that scrolls a page. A thin scroll bar alone did not say
     * so, and settings further down went unnoticed. Nothing shows when everything fits.
     */
    static StackPane withScrollHints(ScrollPane scroll) {
        Region topFade = new Region();
        topFade.getStyleClass().add("scroll-fade-top");
        Region bottomFade = new Region();
        bottomFade.getStyleClass().add("scroll-fade-bottom");
        for (Region fade : new Region[]{topFade, bottomFade}) {
            fade.setMouseTransparent(true);
            fade.setPrefHeight(36);
            fade.setMaxHeight(36);
            fade.setMaxWidth(Double.MAX_VALUE);
        }
        Button more = new Button(Messages.get("scroll.more"), Icons.down());
        more.getStyleClass().add("scroll-more");
        more.setContentDisplay(javafx.scene.control.ContentDisplay.RIGHT);
        more.setFocusTraversable(false);
        more.setTooltip(new Tooltip(Messages.get("scroll.more.tooltip")));
        more.setOnAction(event -> {
            double content = scroll.getContent().getBoundsInLocal().getHeight();
            double view = scroll.getViewportBounds().getHeight();
            if (content > view) {
                double page = (view * 0.85) / (content - view);
                scroll.setVvalue(Math.min(scroll.getVmax(), scroll.getVvalue() + page * scroll.getVmax()));
            }
        });
        StackPane pane = new StackPane(scroll, topFade, bottomFade, more);
        StackPane.setAlignment(topFade, Pos.TOP_CENTER);
        StackPane.setAlignment(bottomFade, Pos.BOTTOM_CENTER);
        // in the corner by the scroll bar, where it covers the least of the content
        StackPane.setAlignment(more, Pos.BOTTOM_RIGHT);
        // keep clear of the scroll bar, which stays usable under the hint
        StackPane.setMargin(topFade, new Insets(0, 14, 0, 0));
        StackPane.setMargin(bottomFade, new Insets(0, 14, 0, 0));
        StackPane.setMargin(more, new Insets(0, 22, 6, 0));
        Runnable update = () -> {
            javafx.scene.Node content = scroll.getContent();
            double contentHeight = content != null ? content.getBoundsInLocal().getHeight() : 0;
            boolean scrollable = contentHeight > scroll.getViewportBounds().getHeight() + 1;
            boolean atTop = scroll.getVvalue() <= scroll.getVmin() + 0.001;
            boolean atBottom = scroll.getVvalue() >= scroll.getVmax() - 0.001;
            topFade.setVisible(scrollable && !atTop);
            bottomFade.setVisible(scrollable && !atBottom);
            more.setVisible(scrollable && !atBottom);
        };
        scroll.vvalueProperty().addListener((obs, was, now) -> update.run());
        scroll.viewportBoundsProperty().addListener((obs, was, now) -> update.run());
        scroll.contentProperty().addListener((obs, was, now) -> {
            if (now != null) {
                now.boundsInLocalProperty().addListener((o, w, n) -> update.run());
            }
            update.run();
        });
        if (scroll.getContent() != null) {
            scroll.getContent().boundsInLocalProperty().addListener((obs, was, now) -> update.run());
        }
        update.run();
        return pane;
    }

    private static Label sectionLabel(String text) {
        Label label = new Label(text);
        label.setStyle("-fx-font-weight: bold; -fx-font-size: 14px;");
        return label;
    }

    /** Saves as soon as the control changes — combo boxes, check boxes and the like. */
    private static void autoSave(javafx.beans.value.ObservableValue<?> value, Runnable save) {
        value.addListener((obs, oldValue, newValue) -> save.run());
    }

    /**
     * Text fields save a moment after typing stops, and immediately on Enter or
     * focus loss, so half-typed values never reach the preferences and no edit is
     * lost when the panel is closed.
     */
    private static void autoSave(TextField field, Runnable save) {
        String[] committed = {field.getText()};
        Runnable commit = () -> {
            if (!Objects.equals(committed[0], field.getText())) {
                committed[0] = field.getText();
                save.run();
            }
        };
        PauseTransition idle = new PauseTransition(Duration.millis(600));
        idle.setOnFinished(event -> commit.run());
        field.textProperty().addListener((obs, oldText, newText) -> idle.playFromStart());
        field.focusedProperty().addListener((obs, was, focused) -> {
            if (!focused) {
                idle.stop();
                commit.run();
            }
        });
        field.setOnAction(event -> {
            idle.stop();
            commit.run();
        });
    }

    /** Sliders save once the drag ends, not on every intermediate value. */
    private static void autoSave(Slider slider, Runnable save) {
        slider.valueProperty().addListener((obs, oldValue, newValue) -> {
            if (!slider.isValueChanging()) {
                save.run();
            }
        });
        slider.valueChangingProperty().addListener((obs, was, changing) -> {
            if (!changing) {
                save.run();
            }
        });
    }

    private static int parseNonNegative(String text) {
        try {
            return Math.max(Integer.parseInt(text.trim()), 0);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private void showEpisodeDetails(FeedItem item) {
        Label meta = new Label();
        StringBuilder metaText = new StringBuilder();
        if (item.getFeed() != null && item.getFeed().getTitle() != null) {
            metaText.append(item.getFeed().getTitle());
        } else if (feedList.getSelectionModel().getSelectedItem() != null) {
            metaText.append(feedList.getSelectionModel().getSelectedItem().getTitle());
        }
        if (item.getPubDate() != null) {
            if (metaText.length() > 0) {
                metaText.append(" · ");
            }
            metaText.append(new SimpleDateFormat("d MMM yyyy", Locale.US).format(item.getPubDate()));
        }
        if (item.getMedia() != null && item.getMedia().getDuration() > 0) {
            metaText.append(" · ").append(formatDuration(item.getMedia().getDuration()));
        }
        meta.setText(metaText.toString());
        meta.setPadding(new Insets(8, 8, 0, 8));
        WebView webView = new WebView();
        showHtml(webView, Shownotes.toPage(item.getTitle(), item.getDescription(), ThemeManager.isDark()));
        Button websiteButton = new Button(Messages.get("episode.website"));
        websiteButton.setDisable(item.getLink() == null || item.getLink().isEmpty());
        websiteButton.setOnAction(event -> getHostServices().showDocument(item.getLink()));
        Button transcriptButton = new Button(Messages.get("episode.transcript"));
        transcriptButton.setDisable(item.getTranscriptUrl() == null || item.getTranscriptUrl().isEmpty());
        transcriptButton.setOnAction(event -> showTranscript(item));
        HBox buttons = new HBox(8, websiteButton, transcriptButton);
        buttons.setPadding(new Insets(8));
        Feed ownFeed = null;
        for (Feed feed : feeds) {
            if (feed.getId() == item.getFeedId()) {
                ownFeed = feed;
                break;
            }
        }
        // Podcasting 2.0: who is on it (the episode's own list, else the podcast's), and how to
        // support the podcast
        java.util.List<de.danoeh.antennapod.model.feed.PodcastPerson> people = item.getPersons() != null
                ? item.getPersons() : ownFeed != null ? ownFeed.getPersons() : null;
        VBox pane = new VBox(4, meta);
        if (people != null && !people.isEmpty()) {
            pane.getChildren().add(peopleRow(people));
        }
        pane.getChildren().addAll(webView, buttons);
        if (ownFeed != null && ownFeed.getPaymentLinks() != null) {
            for (de.danoeh.antennapod.model.feed.FeedFunding funding : ownFeed.getPaymentLinks()) {
                if (funding.url == null || funding.url.isEmpty()) {
                    continue;
                }
                String label = funding.content != null && !funding.content.isBlank()
                        ? funding.content.trim() : Messages.get("episode.support");
                Button support = new Button(label, Icons.favorite());
                support.setTooltip(new Tooltip(funding.url));
                support.setOnAction(event -> getHostServices().showDocument(funding.url));
                buttons.getChildren().add(support);
            }
        }
        java.util.List<de.danoeh.antennapod.model.feed.Soundbite> soundbites = item.getSoundbites();
        if (soundbites != null && !soundbites.isEmpty() && item.getMedia() != null) {
            Label bitesLabel = new Label(Messages.get("episode.soundbites"));
            bitesLabel.setStyle("-fx-font-weight: bold;");
            bitesLabel.setPadding(new Insets(4, 8, 0, 8));
            VBox bites = new VBox(4);
            bites.setPadding(new Insets(0, 8, 0, 8));
            for (de.danoeh.antennapod.model.feed.Soundbite soundbite : soundbites) {
                Button play = new Button(formatDuration(soundbite.startMs) + " · "
                        + (soundbite.title.isEmpty() ? Messages.format("episode.soundbite.highlight", formatDuration(soundbite.durationMs))
                                : soundbite.title), Icons.play());
                play.setTooltip(new Tooltip(Messages.format("episode.soundbite.play_from", formatDuration(soundbite.startMs))));
                play.setOnAction(event -> seekToChapter(item, soundbite.startMs));
                bites.getChildren().add(play);
            }
            pane.getChildren().add(pane.getChildren().size() - 1, bitesLabel);
            pane.getChildren().add(pane.getChildren().size() - 1, bites);
        }
        List<de.danoeh.antennapod.model.feed.Chapter> chapters = item.getChapters();
        if (chapters != null && !chapters.isEmpty()) {
            Label chaptersLabel = new Label(Messages.get("episode.chapters"));
            chaptersLabel.setStyle("-fx-font-weight: bold;");
            chaptersLabel.setPadding(new Insets(4, 8, 0, 8));
            ListView<de.danoeh.antennapod.model.feed.Chapter> chapterList =
                    new ListView<>(FXCollections.observableArrayList(chapters));
            chapterList.setPrefHeight(Math.min(40 + chapters.size() * 28, 180));
            chapterList.setCellFactory(view -> new ListCell<>() {
                @Override
                protected void updateItem(de.danoeh.antennapod.model.feed.Chapter chapter, boolean empty) {
                    super.updateItem(chapter, empty);
                    if (empty || chapter == null) {
                        setText(null);
                        return;
                    }
                    setText(formatDuration(chapter.getStart()) + " · " + chapter.getTitle());
                }
            });
            chapterList.setOnMouseClicked(event -> {
                if (event.getClickCount() == 2) {
                    de.danoeh.antennapod.model.feed.Chapter selected =
                            chapterList.getSelectionModel().getSelectedItem();
                    if (selected != null && item.getMedia() != null) {
                        seekToChapter(item, (int) selected.getStart());
                        hideSidebar();
                    }
                }
            });
            pane.getChildren().add(pane.getChildren().size() - 1, chaptersLabel);
            pane.getChildren().add(pane.getChildren().size() - 1, chapterList);
        }
        VBox.setVgrow(webView, Priority.ALWAYS);
        showSidebar(item.getTitle() != null ? item.getTitle() : Messages.get("episode.title_fallback"), pane);
    }

    /** "Hosts: Ada · Guests: Grace" with each name a link when the feed gives one. */
    private javafx.scene.layout.FlowPane peopleRow(
            java.util.List<de.danoeh.antennapod.model.feed.PodcastPerson> people) {
        javafx.scene.layout.FlowPane row = new javafx.scene.layout.FlowPane(6, 2);
        row.setPadding(new Insets(0, 8, 0, 8));
        java.util.Map<String, java.util.List<de.danoeh.antennapod.model.feed.PodcastPerson>> byRole =
                new java.util.LinkedHashMap<>();
        for (de.danoeh.antennapod.model.feed.PodcastPerson person : people) {
            byRole.computeIfAbsent(person.role, role -> new ArrayList<>()).add(person);
        }
        boolean first = true;
        for (java.util.Map.Entry<String, java.util.List<de.danoeh.antennapod.model.feed.PodcastPerson>> entry
                : byRole.entrySet()) {
            if (!first) {
                row.getChildren().add(new Label("·"));
            }
            first = false;
            Label role = new Label(peopleRoleLabel(entry.getKey(), entry.getValue().size()) + ":");
            role.getStyleClass().add("muted-label");
            row.getChildren().add(role);
            for (de.danoeh.antennapod.model.feed.PodcastPerson person : entry.getValue()) {
                if (person.href != null) {
                    javafx.scene.control.Hyperlink link = new javafx.scene.control.Hyperlink(person.name);
                    link.setPadding(Insets.EMPTY);
                    link.setOnAction(event -> getHostServices().showDocument(person.href));
                    row.getChildren().add(link);
                } else {
                    row.getChildren().add(new Label(person.name));
                }
            }
        }
        return row;
    }

    /** "Host" / "Hosts", "Guest", or the feed's own role name, capitalised. */
    static String peopleRoleLabel(String role, int count) {
        String name = role == null || role.isEmpty() ? "host" : role;
        String label = Character.toUpperCase(name.charAt(0)) + name.substring(1);
        return count > 1 && !label.endsWith("s") ? label + "s" : label;
    }

    private void showTranscript(FeedItem item) {
        setStatus(Messages.get("status.transcript.loading"));
        background.submit(() -> {
            try {
                de.danoeh.antennapod.model.feed.Transcript transcript = TranscriptFetcher.fetch(item);
                Platform.runLater(() -> {
                    ListView<de.danoeh.antennapod.model.feed.TranscriptSegment> list =
                            new ListView<>();
                    for (int i = 0; i < transcript.getSegmentCount(); i++) {
                        list.getItems().add(transcript.getSegmentAt(i));
                    }
                    list.setCellFactory(view -> new ListCell<>() {
                        @Override
                        protected void updateItem(
                                de.danoeh.antennapod.model.feed.TranscriptSegment segment, boolean empty) {
                            super.updateItem(segment, empty);
                            if (empty || segment == null) {
                                setText(null);
                                return;
                            }
                            String speaker = segment.getSpeaker() != null && !segment.getSpeaker().isEmpty()
                                    ? segment.getSpeaker() + ": " : "";
                            setText(formatDuration(segment.getStartTime()) + "  "
                                    + speaker + segment.getWords());
                            setWrapText(true);
                        }
                    });
                    list.setOnMouseClicked(event -> {
                        if (event.getClickCount() == 2) {
                            de.danoeh.antennapod.model.feed.TranscriptSegment selected =
                                    list.getSelectionModel().getSelectedItem();
                            if (selected != null && item.getMedia() != null) {
                                seekToChapter(item, (int) selected.getStartTime());
                                hideSidebar();
                            }
                        }
                    });
                    VBox pane = new VBox(8, list);
                    pane.setPadding(new Insets(8));
                    showSidebar(Messages.format("transcript.title", item.getTitle()), pane);
                });
            } catch (Exception e) {
                setStatus(Messages.format("status.transcript.load_failed", e.getMessage()));
            }
        });
    }

    private void seekToChapter(FeedItem item, int positionMs) {
        FeedMedia current = playback.getCurrentMedia();
        if (current != null && item.getMedia() != null
                && current.getId() == item.getMedia().getId()) {
            playback.seek(positionMs);
        } else {
            playback.playAt(item, new ArrayList<>(visibleEpisodes), positionMs);
        }
    }

    private void showSyncDialog() {
        ComboBox<String> providerBox = new ComboBox<>();
        providerBox.getItems().addAll(Messages.get("sync.provider.disabled"), "gPodder.net", "Nextcloud");
        String provider = DesktopPreferences.getSyncProvider();
        providerBox.setValue("nextcloud".equals(provider) ? "Nextcloud"
                : ("gpodder".equals(provider) ? "gPodder.net" : Messages.get("sync.provider.disabled")));
        TextField hostField = new TextField(DesktopPreferences.getSyncHost());
        hostField.setPromptText(Messages.get("sync.host.prompt"));
        TextField userField = new TextField(DesktopPreferences.getSyncUsername());
        userField.setPromptText(Messages.get("login.username.prompt"));
        javafx.scene.control.PasswordField passField = new javafx.scene.control.PasswordField();
        passField.setText(DesktopPreferences.getSyncPassword());
        passField.setPromptText(Messages.get("sync.password.prompt"));
        TextField deviceField = new TextField(DesktopPreferences.getSyncDeviceCaption());
        Label syncStatus = new Label(Messages.format("sync.status.saved_automatically", syncStatusText()));
        syncStatus.setWrapText(true);
        javafx.scene.control.CheckBox autoSyncBox = new javafx.scene.control.CheckBox(
                Messages.get("sync.auto"));
        autoSyncBox.setSelected(DesktopPreferences.getAutoSyncPlayback());
        Runnable save = () -> {
            String selected = providerBox.getValue();
            DesktopPreferences.setSyncProvider("Nextcloud".equals(selected) ? "nextcloud"
                    : ("gPodder.net".equals(selected) ? "gpodder" : "none"));
            DesktopPreferences.setSyncHost(hostField.getText().trim());
            DesktopPreferences.setSyncUsername(userField.getText().trim());
            DesktopPreferences.setSyncPassword(passField.getText());
            DesktopPreferences.setSyncDeviceCaption(deviceField.getText().trim());
            DesktopPreferences.setAutoSyncPlayback(autoSyncBox.isSelected());
            syncStatus.setText(syncStatusText());
            setStatus(Messages.get("status.sync.settings_saved"));
        };
        autoSave(providerBox.valueProperty(), save);
        autoSave(hostField, save);
        autoSave(userField, save);
        autoSave(passField, save);
        autoSave(deviceField, save);
        autoSave(autoSyncBox.selectedProperty(), save);
        Button testButton = new Button(Messages.get("sync.test_login"));
        testButton.setOnAction(event -> {
            save.run();
            syncStatus.setText(Messages.get("sync.testing_login"));
            Node original = showButtonSpinner(testButton);
            background.submit(() -> {
                try {
                    syncManager.testLogin();
                    Platform.runLater(() -> syncStatus.setText(Messages.get("sync.login_ok")));
                } catch (Exception e) {
                    Platform.runLater(() -> syncStatus.setText(Messages.format("sync.login_failed", e.getMessage())));
                } finally {
                    Platform.runLater(() -> hideButtonSpinner(testButton, original));
                }
            });
        });
        Button syncNowButton = new Button(Messages.get("sync.now"));
        syncNowButton.setOnAction(event -> {
            save.run();
            Node[] original = new Node[1];
            runSync(
                    () -> {
                        original[0] = showButtonSpinner(syncNowButton);
                        syncStatus.setText(Messages.get("sync.syncing"));
                        setStatus(Messages.get("status.sync.started"));
                    },
                    message -> {
                        syncStatus.setText(message);
                        hideButtonSpinner(syncNowButton, original[0]);
                    });
        });
        Button devicesButton = new Button(Messages.get("sync.import_devices"));
        devicesButton.setOnAction(event -> {
            save.run();
            showDevicesDialog();
        });
        javafx.scene.layout.GridPane grid = new javafx.scene.layout.GridPane();
        grid.setHgap(8);
        grid.setVgap(8);
        grid.setPadding(new Insets(12));
        grid.add(new Label(Messages.get("sync.provider")), 0, 0);
        grid.add(providerBox, 1, 0);
        grid.add(new Label(Messages.get("common.host_label")), 0, 1);
        grid.add(hostField, 1, 1);
        grid.add(new Label(Messages.get("common.username_label")), 0, 2);
        grid.add(userField, 1, 2);
        grid.add(new Label(Messages.get("common.password_label")), 0, 3);
        grid.add(passField, 1, 3);
        grid.add(new Label(Messages.get("sync.device_name")), 0, 4);
        grid.add(deviceField, 1, 4);
        grid.add(new HBox(8, testButton, syncNowButton), 0, 5, 2, 1);
        grid.add(devicesButton, 0, 6, 2, 1);
        grid.add(autoSyncBox, 0, 7, 2, 1);
        grid.add(syncStatus, 0, 8, 2, 1);
        showModal(Messages.get("sync.title"), new VBox(grid));
    }

    private String deviceImportHint() {
        try {
            List<de.danoeh.antennapod.net.sync.gpoddernet.model.GpodnetDevice> devices =
                    syncManager.listDevices();
            for (de.danoeh.antennapod.net.sync.gpoddernet.model.GpodnetDevice device : devices) {
                if (!DesktopPreferences.getSyncDeviceId().equals(device.getId())
                        && device.getSubscriptions() > 0) {
                    return Messages.get("sync.hint.other_devices");
                }
            }
        } catch (Exception e) {
            // ignore hint on error
        }
        return "";
    }

    private void showDevicesDialog() {
        Label status = new Label(Messages.get("devices.loading"));
        status.setWrapText(true);
        ListView<de.danoeh.antennapod.net.sync.gpoddernet.model.GpodnetDevice> list = new ListView<>();
        list.setCellFactory(view -> fullWidthCell(new ListCell<>() {
            @Override
            protected void updateItem(
                    de.danoeh.antennapod.net.sync.gpoddernet.model.GpodnetDevice device, boolean empty) {
                super.updateItem(device, empty);
                if (empty || device == null) {
                    setText(null);
                    setGraphic(null);
                    return;
                }
                Label title = new Label(Messages.format("devices.row", device.getCaption(), device.getType(),
                        device.getSubscriptions()));
                title.setWrapText(true);
                Button importButton = new Button(Messages.get("devices.import"));
                boolean own = DesktopPreferences.getSyncDeviceId().equals(device.getId());
                importButton.setDisable(own || device.getSubscriptions() <= 0);
                importButton.setOnAction(event -> importDeviceSubscriptions(device, status));
                HBox row = new HBox(8, title, importButton);
                HBox.setHgrow(title, Priority.ALWAYS);
                setGraphic(row);
                setText(null);
            }
        }));
        VBox pane = new VBox(8, status, list);
        pane.setPadding(new Insets(8));
        list.setPrefHeight(280);
        VBox.setVgrow(list, Priority.ALWAYS);
        showModal(Messages.get("devices.title"), pane);
        background.submit(() -> {
            try {
                List<de.danoeh.antennapod.net.sync.gpoddernet.model.GpodnetDevice> devices =
                        syncManager.listDevices();
                Platform.runLater(() -> {
                    list.getItems().setAll(devices);
                    status.setText(devices.isEmpty()
                            ? Messages.get("devices.none")
                            : Messages.get("devices.select"));
                });
            } catch (Exception e) {
                Platform.runLater(() -> status.setText(Messages.format("devices.load_failed", e.getMessage())));
            }
        });
    }

    private void importDeviceSubscriptions(
            de.danoeh.antennapod.net.sync.gpoddernet.model.GpodnetDevice device,
            Label status) {
        status.setText(Messages.format("devices.importing", device.getCaption()));
        background.submit(() -> {
            try {
                int added = syncManager.importFromDevice(device.getId());
                Platform.runLater(() -> {
                    status.setText(Messages.format("devices.imported", added, device.getCaption()));
                    reloadFeeds(null);
                });
                setStatus(Messages.format("devices.imported", added, device.getCaption()));
            } catch (Exception e) {
                Platform.runLater(() -> status.setText(Messages.format("devices.import_failed", e.getMessage())));
            }
        });
    }

    private String syncStatusText() {
        if (!DesktopPreferences.isSyncEnabled()) {
            return Messages.get("sync.status.disabled");
        }
        return Messages.format("sync.status.enabled", DesktopPreferences.getSyncProvider(),
                DesktopPreferences.getSyncUsername()) + lastSyncSuffix();
    }

    private String syncResultText(SyncManager.SyncResult result) {
        StringBuilder message = new StringBuilder(Messages.format("sync.result",
                result.subscriptionsAdded, result.actionsUploaded, result.actionsApplied));
        if (!result.playedItemIds.isEmpty()) {
            message.append(" · ").append(Messages.format("sync.result.marked_finished",
                    result.playedItemIds.size()));
        }
        if (!result.unplayedItemIds.isEmpty()) {
            message.append(" · ").append(Messages.format("sync.result.marked_unfinished",
                    result.unplayedItemIds.size()));
        }
        message.append(".").append(lastSyncSuffix());
        if (result.subscriptionsAdded == 0 && result.actionsUploaded == 0
                && result.actionsApplied == 0
                && "gpodder".equals(DesktopPreferences.getSyncProvider())) {
            message.append(deviceImportHint());
        }
        return message.toString();
    }

    private static String lastSyncSuffix() {
        long last = DesktopPreferences.getLastSyncTime();
        if (last <= 0) {
            return " " + Messages.get("sync.never_synced");
        }
        return " " + Messages.format("sync.last_synced",
                new SimpleDateFormat("d MMM HH:mm", Locale.US).format(new Date(last)));
    }

    private void updateSyncButtonTooltip() {
        if (syncButton == null) {
            return;
        }
        long last = DesktopPreferences.getLastSyncTime();
        syncButton.setTooltip(new Tooltip(last <= 0 ? Messages.get("toolbar.sync.never_synced")
                : Messages.format("toolbar.sync.last_synced",
                        new SimpleDateFormat("d MMM HH:mm", Locale.US).format(new Date(last)))));
    }

    private void scheduleAutoSync() {
        if (syncManager == null || !DesktopPreferences.isSyncEnabled()
                || !DesktopPreferences.getAutoSyncPlayback()) {
            return;
        }
        if (!autoSyncPending.compareAndSet(false, true)) {
            return;
        }
        try {
            if (autoSyncScheduler == null) {
                autoSyncScheduler =
                        java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                            Thread thread = new Thread(r, "auto-sync");
                            thread.setDaemon(true);
                            return thread;
                        });
            }
            autoSyncScheduler.schedule(() -> {
                autoSyncPending.set(false);
                if (!syncRunning.get()) {
                    runSync(null, null);
                }
            }, 20, java.util.concurrent.TimeUnit.SECONDS);
        } catch (java.util.concurrent.RejectedExecutionException e) {
            autoSyncPending.set(false);
        }
    }

    private void runSync(Runnable onStart, java.util.function.Consumer<String> onFinish) {
        if (!syncRunning.compareAndSet(false, true)) {
            if (onFinish != null) {
                onFinish.accept(Messages.get("status.sync.already_running"));
            }
            return;
        }
        if (onStart != null) {
            onStart.run();
        }
        background.submit(() -> {
            try {
                SyncManager.SyncResult result = syncManager.sync();
                DesktopPreferences.setLastSyncTime(System.currentTimeMillis());
                // An episode can finish (or be marked) while this sync was in flight. Its action
                // was queued too late for this round, so line up another one: the local state is
                // already written, and the queued action uploads on the follow-up.
                try {
                    if (!database.getQueuedSyncActions().isEmpty()) {
                        scheduleAutoSync();
                    }
                } catch (Exception ignored) {
                    // the queued actions stay queued for the next trigger
                }
                String message = syncResultText(result);
                String summary = result.playedItemIds.isEmpty()
                        ? Messages.get("status.sync.finished")
                        : Messages.format("status.sync.finished_marked", result.playedItemIds.size());
                Platform.runLater(() -> {
                    syncedItemIds.clear();
                    syncedItemIds.addAll(result.syncedItemIds);
                    updateGhostMarker(playback.getPosition(), playback.getDuration());
                    reloadSyncedMarker();
                    if (onFinish != null) {
                        onFinish.accept(message);
                    }
                    updateSyncButtonTooltip();
                    reloadFeeds(null);
                    // the sync wrote the new play states straight to the database, while the rows
                    // still hold the items as they were when the feed was opened: re-read them,
                    // because re-rendering only redraws what is now stale
                    Feed open = selectedFeed;
                    if (open != null) {
                        loadEpisodes(open);
                    } else {
                        episodeList.refresh();
                    }
                });
                setStatus(summary);
            } catch (Exception e) {
                Platform.runLater(() -> {
                    if (onFinish != null) {
                        onFinish.accept(Messages.format("status.sync.failed", e.getMessage()));
                    }
                });
                setStatus(Messages.format("status.sync.failed", e.getMessage()));
            } finally {
                syncRunning.set(false);
            }
        });
    }

    private void search(String query) {
        if (query.isEmpty()) {
            return;
        }
        setStatus(Messages.format("status.search.started", query));
        background.submit(() -> doSearch(query));
    }

    private void startSearch(Button button, String rawQuery) {
        String query = rawQuery.trim();
        if (query.isEmpty()) {
            return;
        }
        setStatus(Messages.format("status.search.started", query));
        spinWhile(button, () -> doSearch(query));
    }

    private void doSearch(String query) {
        try {
            List<PodcastSearchResult> results =
                    new CombinedSearcher().search(query).blockingGet();
            Platform.runLater(() -> showSearchResults(query, results));
            setStatus(Messages.format("status.search.found", results.size(), query));
        } catch (Exception e) {
            setStatus(Messages.format("status.search.failed", e.getMessage()));
        }
    }

    private void showSearchResults(String query, List<PodcastSearchResult> results) {
        ObservableList<PodcastSearchResult> items = FXCollections.observableArrayList(results);
        ListView<PodcastSearchResult> list = new ListView<>(items);
        list.setCellFactory(view -> fullWidthCell(new ListCell<>() {
            private final ImageView art = new ImageView();
            private final Label title = new Label();

            {
                art.setFitWidth(48);
                art.setFitHeight(48);
                art.setPreserveRatio(true);
                art.setSmooth(true);
                title.setWrapText(true);
            }

            @Override
            protected void updateItem(PodcastSearchResult result, boolean empty) {
                super.updateItem(result, empty);
                if (empty || result == null) {
                    setText(null);
                    setGraphic(null);
                    return;
                }
                title.setText(result.title
                        + (result.author != null && !result.author.isEmpty() ? " — " + result.author : ""));
                Button subscribeButton = new Button(Messages.get("common.subscribe"));
                subscribeButton.setOnAction(event -> {
                    if (result.feedUrl != null) {
                        hideSidebar();
                        subscribe(result.feedUrl);
                    }
                });
                // a Button fires on release, so swallowing the click here only stops the row
                // underneath from also opening the details modal
                subscribeButton.addEventFilter(MouseEvent.MOUSE_CLICKED, MouseEvent::consume);
                boolean hasImage = result.imageUrl != null && !result.imageUrl.isEmpty();
                art.setImage(hasImage ? ImageCache.get(result.imageUrl, 48, 48) : null);
                art.setVisible(hasImage);
                art.setManaged(hasImage);
                Node artNode = hasImage ? art : new Region();
                if (!hasImage) {
                    ((Region) artNode).setMinSize(48, 48);
                    ((Region) artNode).setMaxSize(48, 48);
                    artNode.setStyle("-fx-background-color: -fx-control-inner-background;"
                            + "-fx-background-radius: 4;");
                }
                VBox textBlock = new VBox(4, title, subscribeButton);
                HBox row = new HBox(8, artNode, textBlock);
                HBox.setHgrow(textBlock, Priority.ALWAYS);
                setGraphic(row);
                setText(null);
            }
        }));
        list.setOnMouseClicked(event -> {
            PodcastSearchResult selected = list.getSelectionModel().getSelectedItem();
            if (event.getButton() == MouseButton.PRIMARY && selected != null) {
                showPodcastDetails(selected);
            }
        });
        VBox pane = new VBox(8, list);
        pane.setPadding(new Insets(8));
        VBox.setVgrow(list, Priority.ALWAYS);
        showSidebar(Messages.format("search.results.title", query), pane);
    }

    /**
     * The podcast behind a search result: what the directory gave us straight away, and the
     * description, website and episode count once the feed itself has been fetched. Nothing is
     * stored - the feed is only read so the user can decide whether to subscribe.
     */
    private void showPodcastDetails(PodcastSearchResult result) {
        Label author = new Label(result.author == null || result.author.isEmpty()
                ? Messages.get("podcast.unknown_author") : result.author);
        author.getStyleClass().add("muted-label");
        Label meta = new Label(Messages.get("podcast.loading_details"));
        meta.getStyleClass().add("muted-label");
        meta.setWrapText(true);

        ImageView art = new ImageView();
        art.setFitWidth(96);
        art.setFitHeight(96);
        art.setPreserveRatio(true);
        art.setSmooth(true);
        if (result.imageUrl != null && !result.imageUrl.isEmpty()) {
            art.setImage(ImageCache.get(result.imageUrl, 96, 96));
        }

        Label heading = new Label(result.title);
        heading.setWrapText(true);
        heading.setStyle("-fx-font-size: 15px; -fx-font-weight: bold;");
        VBox headingBlock = new VBox(4, heading, author, meta);
        HBox.setHgrow(headingBlock, Priority.ALWAYS);
        HBox header = new HBox(12, art, headingBlock);

        Button subscribeButton = new Button(Messages.get("common.subscribe"));
        subscribeButton.setDefaultButton(true);
        subscribeButton.setDisable(result.feedUrl == null || result.feedUrl.isEmpty());
        subscribeButton.setOnAction(event -> {
            closeTopModal();
            hideSidebar();
            subscribe(result.feedUrl);
        });
        markSubscribed(subscribeButton, result.feedUrl);
        Button websiteButton = new Button(Messages.get("podcast.website"));
        websiteButton.setDisable(true);
        Button copyButton = new Button(Messages.get("podcast.copy_feed_url"));
        copyButton.setDisable(result.feedUrl == null || result.feedUrl.isEmpty());
        copyButton.setOnAction(event -> {
            javafx.scene.input.ClipboardContent content = new javafx.scene.input.ClipboardContent();
            content.putString(result.feedUrl);
            javafx.scene.input.Clipboard.getSystemClipboard().setContent(content);
            setStatus(Messages.get("status.feed_url_copied"));
        });
        HBox buttons = new HBox(8, subscribeButton, websiteButton, copyButton);

        WebView description = new WebView();
        description.setPrefHeight(260);
        showHtml(description, Shownotes.toPage(null, "<p>" + Messages.get("podcast.loading_description") + "</p>",
                ThemeManager.isDark()));

        Label feedUrlLabel = new Label(result.feedUrl);
        feedUrlLabel.getStyleClass().add("muted-label");
        feedUrlLabel.setWrapText(true);

        VBox pane = new VBox(12, header, buttons, description, feedUrlLabel);
        pane.setPadding(new Insets(8));
        VBox.setVgrow(description, Priority.ALWAYS);
        showModal(result.title, pane);

        if (result.feedUrl == null || result.feedUrl.isEmpty()) {
            meta.setText(Messages.get("podcast.no_feed_address"));
            showHtml(description, Shownotes.toPage(null, "", ThemeManager.isDark()));
            return;
        }
        background.submit(() -> {
            try {
                Feed feed = feedUpdater.preview(result.feedUrl);
                Platform.runLater(() -> {
                    meta.setText(describePodcast(feed));
                    String html = feed.getDescription() == null || feed.getDescription().isEmpty()
                            ? "<p><i>" + Messages.get("podcast.no_description") + "</i></p>" : feed.getDescription();
                    showHtml(description, Shownotes.toPage(null, html, ThemeManager.isDark()));
                    String link = feed.getLink();
                    websiteButton.setDisable(link == null || link.isEmpty());
                    websiteButton.setOnAction(event -> getHostServices().showDocument(link));
                    // the feed may redirect, so re-check against the address actually fetched
                    markSubscribed(subscribeButton, feed.getDownloadUrl());
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    meta.setText(Messages.format("podcast.load_failed", e.getMessage()));
                    showHtml(description, Shownotes.toPage(null, "", ThemeManager.isDark()));
                });
            }
        });
    }

    /** Episode count, language and the newest episode, as far as the feed says. */
    static String describePodcast(Feed feed) {
        List<String> parts = new ArrayList<>();
        int episodes = feed.getItems() == null ? 0 : feed.getItems().size();
        parts.add(episodes == 1 ? Messages.get("count.episode.one")
                : Messages.format("count.episode.other", episodes));
        if (feed.getLanguage() != null && !feed.getLanguage().isEmpty()) {
            parts.add(feed.getLanguage());
        }
        Date latest = newestPubDate(feed);
        if (latest != null) {
            parts.add(Messages.format("podcast.latest", new SimpleDateFormat("d MMM yyyy", Locale.US).format(latest)));
        }
        return String.join(" \u00b7 ", parts);
    }

    static Date newestPubDate(Feed feed) {
        Date newest = null;
        if (feed.getItems() == null) {
            return null;
        }
        for (FeedItem item : feed.getItems()) {
            Date pubDate = item.getPubDate();
            if (pubDate != null && (newest == null || pubDate.after(newest))) {
                newest = pubDate;
            }
        }
        return newest;
    }

    /** Turns the subscribe button into a disabled marker when this feed is already subscribed. */
    private void markSubscribed(Button subscribeButton, String feedUrl) {
        if (feedUrl == null || feedUrl.isEmpty() || !isSubscribed(feedUrl)) {
            return;
        }
        subscribeButton.setText(Messages.get("podcast.subscribed"));
        subscribeButton.setDisable(true);
        subscribeButton.setDefaultButton(false);
    }

    private boolean isSubscribed(String feedUrl) {
        for (Feed feed : feeds) {
            if (feedUrl.equalsIgnoreCase(feed.getDownloadUrl())) {
                return true;
            }
        }
        return false;
    }

    private void togglePlayed(FeedItem item) {
        applyPlayedState(actionTargets(item), !item.isPlayed());
    }

    private void applyPlayedState(List<FeedItem> items, boolean played) {
        background.submit(() -> {
            try {
                for (FeedItem item : items) {
                    item.setPlayed(played);
                    database.setItemState(item.getId(), item.getPlayState());
                    syncManager.recordPlayedState(item, played);
                }
                setStatus(played ? Messages.format("status.marked_played", episodeCountText(items))
                        : Messages.format("status.marked_unplayed", episodeCountText(items)));
                Platform.runLater(this::refilterEpisodes);
                refreshFeedCounts();
            } catch (Exception e) {
                setStatus(Messages.format("status.episodes.update_failed", e.getMessage()));
            }
        });
    }

    private List<FeedItem> actionTargets(FeedItem item) {
        List<FeedItem> selected = new ArrayList<>(episodeList.getSelectionModel().getSelectedItems());
        if (item != null && selected.size() > 1 && selected.contains(item)) {
            return selected;
        }
        if (item != null) {
            List<FeedItem> single = new ArrayList<>();
            single.add(item);
            return single;
        }
        return selected;
    }

    private static String episodeCountText(List<FeedItem> items) {
        return episodeCountText(items.size());
    }

    private static String episodeCountText(int count) {
        return count == 1 ? Messages.get("count.episode.one") : Messages.format("count.episode.other", count);
    }

    private void enqueue(FeedItem item) {
        enqueueItems(actionTargets(item));
    }

    private void enqueueItems(List<FeedItem> targets) {
        background.submit(() -> {
            try {
                for (FeedItem target : targets) {
                    database.addToQueue(target.getId());
                }
                setStatus(Messages.format("status.queue.added", episodeCountText(targets)));
            } catch (Exception e) {
                setStatus(Messages.format("status.queue.add_failed", e.getMessage()));
            }
        });
    }

    private void dequeue(FeedItem item) {
        dequeueItems(actionTargets(item));
    }

    private void dequeueItems(List<FeedItem> targets) {
        background.submit(() -> {
            try {
                for (FeedItem target : targets) {
                    database.removeFromQueue(target.getId());
                }
                setStatus(Messages.format("status.queue.removed", episodeCountText(targets)));
            } catch (Exception e) {
                setStatus(Messages.format("status.queue.remove_failed", e.getMessage()));
            }
        });
    }

    private void showQueue() {
        background.submit(() -> {
            try {
                List<FeedItem> queue = database.getQueue();
                Platform.runLater(() -> showQueueDialog(queue));
            } catch (Exception e) {
                setStatus(Messages.format("status.queue.load_failed", e.getMessage()));
            }
        });
    }

    private void showQueueDialog(List<FeedItem> initialQueue) {
        ObservableList<FeedItem> queueItems = FXCollections.observableArrayList(initialQueue);
        ListView<FeedItem> queueList = new ListView<>(queueItems);
        queueList.setCellFactory(view -> fullWidthCell(new ListCell<>() {
            {
                // drag a row to reorder; dropping on a row puts the episode before it, and on
                // the empty space below the last one, at the end
                setOnDragDetected(event -> {
                    if (getItem() == null) {
                        return;
                    }
                    javafx.scene.input.Dragboard board = startDragAndDrop(javafx.scene.input.TransferMode.MOVE);
                    ClipboardContent content = new ClipboardContent();
                    content.putString(QUEUE_DRAG_PREFIX + getItem().getId());
                    board.setContent(content);
                    board.setDragView(snapshot(null, null));
                    event.consume();
                });
                setOnDragOver(event -> {
                    if (draggedQueueItemId(event.getDragboard()) >= 0) {
                        event.acceptTransferModes(javafx.scene.input.TransferMode.MOVE);
                        setStyle("-fx-border-color: -fx-accent transparent transparent transparent;"
                                + " -fx-border-width: 2 0 0 0;");
                    }
                    event.consume();
                });
                setOnDragExited(event -> setStyle(""));
                setOnDragDropped(event -> {
                    setStyle("");
                    long draggedId = draggedQueueItemId(event.getDragboard());
                    boolean done = draggedId >= 0
                            && dropQueueItem(draggedId, isEmpty() ? queueItems.size() : getIndex(), queueItems);
                    event.setDropCompleted(done);
                    event.consume();
                });
            }

            @Override
            protected void updateItem(FeedItem item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                    return;
                }
                Label title = new Label(item.getTitle());
                title.setWrapText(true);
                Button upButton = iconButton(Icons.up(), Messages.get("queue.move_up"));
                upButton.setOnAction(event -> moveQueueItem(item, true, queueItems));
                Button downButton = iconButton(Icons.down(), Messages.get("queue.move_down"));
                downButton.setOnAction(event -> moveQueueItem(item, false, queueItems));
                Button removeButton = new Button(Messages.get("queue.remove"), Icons.remove());
                removeButton.setOnAction(event -> removeQueueItem(item, queueItems));
                HBox row = new HBox(8, title, upButton, downButton, removeButton);
                HBox.setHgrow(title, Priority.ALWAYS);
                setGraphic(row);
                setText(null);
            }
        }));
        queueList.setOnMouseClicked(event -> {
            if (event.getClickCount() == 2) {
                FeedItem selected = queueList.getSelectionModel().getSelectedItem();
                if (selected != null && selected.getMedia() != null) {
                    playback.play(selected, new ArrayList<>(queueItems));
                }
            }
        });
        Button playAllButton = new Button(Messages.get("queue.play_all"));
        playAllButton.setOnAction(event -> {
            if (!queueItems.isEmpty() && queueItems.get(0).getMedia() != null) {
                playback.play(queueItems.get(0), new ArrayList<>(queueItems));
            }
        });
        Button clearButton = new Button(Messages.get("queue.clear"));
        clearButton.setOnAction(event -> background.submit(() -> {
            try {
                database.clearQueue();
                Platform.runLater(queueItems::clear);
            } catch (Exception e) {
                setStatus(Messages.format("status.queue.clear_failed", e.getMessage()));
            }
        }));
        HBox buttons = new HBox(8, playAllButton, clearButton);
        buttons.setPadding(new Insets(8, 0, 0, 0));
        VBox pane = new VBox(8, queueList, buttons);
        pane.setPadding(new Insets(8));
        VBox.setVgrow(queueList, Priority.ALWAYS);
        showSidebar(Messages.get("queue.title"), pane);
    }

    /** Marks a queue drag, so text dragged in from elsewhere is not taken for an episode. */
    private static final String QUEUE_DRAG_PREFIX = "antennapod-queue-item:";

    private static long draggedQueueItemId(javafx.scene.input.Dragboard board) {
        if (board == null || !board.hasString() || !board.getString().startsWith(QUEUE_DRAG_PREFIX)) {
            return -1;
        }
        try {
            return Long.parseLong(board.getString().substring(QUEUE_DRAG_PREFIX.length()));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * Puts a dragged episode before the one at {@code dropIndex} (or last, past the end): in the
     * list right away, then in the database, re-read afterwards so both agree.
     */
    private boolean dropQueueItem(long itemId, int dropIndex, ObservableList<FeedItem> queueItems) {
        int from = -1;
        for (int i = 0; i < queueItems.size(); i++) {
            if (queueItems.get(i).getId() == itemId) {
                from = i;
                break;
            }
        }
        if (from < 0) {
            return false;
        }
        int to = queueDropTarget(from, dropIndex, queueItems.size());
        if (to == from) {
            return true;
        }
        FeedItem moved = queueItems.remove(from);
        queueItems.add(to, moved);
        background.submit(() -> {
            try {
                database.moveQueueItemTo(itemId, to);
                List<FeedItem> queue = database.getQueue();
                Platform.runLater(() -> queueItems.setAll(queue));
            } catch (Exception e) {
                setStatus(Messages.format("status.queue.reorder_failed", e.getMessage()));
            }
        });
        return true;
    }

    /**
     * Where an item dragged from {@code from} ends up when dropped on the row at {@code dropIndex}
     * (before it) or past the last row: taking it out first shifts the rows after it up by one.
     */
    static int queueDropTarget(int from, int dropIndex, int size) {
        int target = Math.max(0, Math.min(dropIndex, size));
        return target > from ? target - 1 : target;
    }

    private void moveQueueItem(FeedItem item, boolean up, ObservableList<FeedItem> queueItems) {
        background.submit(() -> {
            try {
                database.moveQueueItem(item.getId(), up);
                List<FeedItem> queue = database.getQueue();
                Platform.runLater(() -> queueItems.setAll(queue));
            } catch (Exception e) {
                setStatus(Messages.format("status.queue.reorder_failed", e.getMessage()));
            }
        });
    }

    private void removeQueueItem(FeedItem item, ObservableList<FeedItem> queueItems) {
        background.submit(() -> {
            try {
                database.removeFromQueue(item.getId());
                Platform.runLater(() -> queueItems.remove(item));
            } catch (Exception e) {
                setStatus(Messages.format("status.queue.remove_failed", e.getMessage()));
            }
        });
    }

    private void downloadOrDelete(FeedItem item) {
        FeedMedia media = item.getMedia();
        if (media == null) {
            return;
        }
        List<FeedItem> targets = actionTargets(item);
        if (downloader.isDownloading(media.getId())) {
            for (FeedItem target : targets) {
                if (target.getMedia() != null) {
                    downloader.cancel(target.getMedia().getId());
                }
            }
            return;
        }
        if (media.localFileAvailable() && media.getLocalFileUrl() != null) {
            deleteDownloads(targets);
            return;
        }
        enqueueDownloads(targets);
    }

    private void enqueueDownloads(List<FeedItem> items) {
        int queued = 0;
        for (FeedItem item : items) {
            FeedMedia media = item.getMedia();
            if (media == null || media.getDownloadUrl() == null
                    || media.localFileAvailable() || downloader.isDownloading(media.getId())) {
                continue;
            }
            downloadProgress.put(media.getId(), 0);
            downloader.enqueue(media, this);
            queued++;
        }
        if (queued == 0) {
            setStatus(Messages.get("status.download.nothing"));
            return;
        }
        setStatus(Messages.format("status.download.started", episodeCountText(queued)));
        episodeList.refresh();
    }

    private void deleteDownloads(List<FeedItem> items) {
        deleteDownloads(items, null);
    }

    /** Deletes the files, then runs {@code after} (if any) on the FX thread. */
    private void deleteDownloads(List<FeedItem> items, Runnable after) {
        List<FeedMedia> deletable = new ArrayList<>();
        for (FeedItem item : items) {
            FeedMedia media = item.getMedia();
            if (media != null && media.localFileAvailable() && media.getLocalFileUrl() != null
                    && !LocalFolderFeeds.isLocalMedia(media)) {
                deletable.add(media);
            }
        }
        if (deletable.isEmpty()) {
            setStatus(Messages.get("status.delete.nothing"));
            return;
        }
        background.submit(() -> {
            try {
                int deleted = 0;
                FeedMedia inUse = null;
                for (FeedMedia media : deletable) {
                    if (!deleteDownloadFile(media.getLocalFileUrl())) {
                        inUse = media;
                        continue;
                    }
                    deleted++;
                    media.setLocalFileUrl(null);
                    database.clearMediaDownload(media.getId());
                    if (deleteCachedCopy(media)) {
                        database.setMediaCacheFile(media.getId(), null);
                    }
                }
                if (inUse != null) {
                    setStatus(Messages.format("status.delete.in_use", inUse.getHumanReadableIdentifier()));
                } else {
                    setStatus(Messages.format("status.delete.done", deleted == 1
                            ? Messages.get("count.episode.one") : Messages.format("count.episode.other", deleted)));
                }
            } catch (Exception e) {
                setStatus(Messages.format("status.delete.failed", e.getMessage()));
            }
            Platform.runLater(() -> {
                refilterEpisodes();
                if (after != null) {
                    after.run();
                }
            });
        });
    }

    /**
     * Deletes a downloaded episode file, retrying for a few seconds: a player that has just
     * finished it is disposed on its own thread and holds the file open until then, and on
     * Windows an open file cannot be deleted. False if it is still there, so the caller can keep
     * the download recorded instead of leaving an orphaned file behind. Background threads only.
     */
    private static boolean deleteDownloadFile(String path) {
        // the last line of defence: only files in the app's own download and cache folders
        // are ever deleted; a local folder's files (or anything else) are the user's
        if (!LocalFolderFeeds.isAppOwned(path)) {
            return false;
        }
        File file = new File(path);
        for (int attempt = 0; attempt < 10; attempt++) {
            if (!file.exists() || file.delete()) {
                return true;
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return !file.exists();
    }

    /** Deletes the playback cache's copy of an episode; true if there was one. */
    private static boolean deleteCachedCopy(FeedMedia media) {
        if (!LocalFolderFeeds.isAppOwned(media.getCacheFileUrl())) {
            return false;
        }
        new File(media.getCacheFileUrl()).delete();
        media.setCacheFileUrl(null);
        return true;
    }

    private boolean hasDownloadable(List<FeedItem> items) {
        for (FeedItem item : items) {
            FeedMedia media = item.getMedia();
            if (media != null && media.getDownloadUrl() != null && !media.localFileAvailable()
                    && !downloader.isDownloading(media.getId())) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasDownloaded(List<FeedItem> items) {
        for (FeedItem item : items) {
            FeedMedia media = item.getMedia();
            if (media != null && media.localFileAvailable() && media.getLocalFileUrl() != null
                    && !LocalFolderFeeds.isLocalMedia(media)) {
                return true;
            }
        }
        return false;
    }

    private void autoDeleteFinished(FeedMedia media) {
        background.submit(() -> {
            try {
                if (media.getItem() == null || media.getItem().getFeedId() == 0) {
                    return;
                }
                if (LocalFolderFeeds.isLocalMedia(media)) {
                    // auto-delete is for downloads; a local folder's file stays where it is
                    return;
                }
                FeedPrefs prefs = database.getFeedPrefs(media.getItem().getFeedId());
                if (!prefs.effectiveAutoDelete(DesktopPreferences.getAutoDeleteDefault())) {
                    return;
                }
                if (media.getLocalFileUrl() != null) {
                    if (!deleteDownloadFile(media.getLocalFileUrl())) {
                        setStatus(Messages.format("status.auto_delete.in_use", media.getHumanReadableIdentifier()));
                        return;
                    }
                    media.setLocalFileUrl(null);
                    database.clearMediaDownload(media.getId());
                    setStatus(Messages.format("status.auto_delete.done", media.getHumanReadableIdentifier()));
                    Platform.runLater(episodeList::refresh);
                }
            } catch (Exception e) {
                setStatus(Messages.format("status.auto_delete.failed", e.getMessage()));
            }
        });
    }

    /**
     * Caches the episode that just started and prefetches the ones queued behind it, so the next
     * episode plays from disk instead of the network.
     */
    private void cachePlaybackStarted(FeedMedia media) {
        if (!DesktopPreferences.getEpisodeCacheEnabled()) {
            return;
        }
        episodeCache.cache(media);
        int prefetch = DesktopPreferences.getEpisodeCachePrefetchCount();
        if (prefetch > 0) {
            for (FeedItem upcoming : playback.upcomingQueue(prefetch)) {
                episodeCache.cache(upcoming.getMedia());
            }
        }
    }

    /** Drops the cached copy of a finished episode; keeps the played state and resume position. */
    private void cachePlaybackFinished(FeedMedia media) {
        if (!DesktopPreferences.getEpisodeCacheRemoveAfterFinish() || media.getCacheFileUrl() == null) {
            return;
        }
        background.submit(() -> {
            // the cache retries on its own while the player still holds the file
            if (episodeCache.evict(media)) {
                Platform.runLater(episodeList::refresh);
            }
        });
    }

    /** Episodes the cache must never evict: the one playing and the ones queued right behind it. */
    private Set<Long> protectedMediaIds() {
        Set<Long> ids = new HashSet<>();
        FeedMedia current = playback.getCurrentMedia();
        if (current != null) {
            ids.add(current.getId());
        }
        for (FeedItem upcoming : playback.upcomingQueue(5)) {
            if (upcoming.getMedia() != null) {
                ids.add(upcoming.getMedia().getId());
            }
        }
        return ids;
    }

    private class QuietDownloadListener implements EpisodeDownloader.ProgressListener {
        private final FeedItem item;

        QuietDownloadListener(FeedItem item) {
            this.item = item;
        }

        @Override
        public void onProgress(long mediaId, long bytesRead, long totalBytes) {
        }

        @Override
        public void onFinished(long mediaId, File file) {
            setStatus(Messages.format("status.autodownload.done", item.getTitle()));
            Platform.runLater(episodeList::refresh);
        }

        @Override
        public void onError(long mediaId, Exception e) {
            setStatus(Messages.format("status.autodownload.item_failed", item.getTitle(), e.getMessage()));
        }
    }

    @Override
    public void onProgress(long mediaId, long bytesRead, long totalBytes) {
        int percent = totalBytes > 0 ? (int) (bytesRead * 100 / totalBytes) : -1;
        Integer previous = downloadProgress.get(mediaId);
        // an unknown size reports -1 on every chunk; only a change is worth redrawing the list
        boolean changed = previous == null
                || (percent < 0 ? previous >= 0 : previous < 0 || percent - previous >= 5);
        if (changed) {
            downloadProgress.put(mediaId, percent);
            Platform.runLater(episodeList::refresh);
        }
    }

    @Override
    public void onFinished(long mediaId, File file) {
        downloadProgress.remove(mediaId);
        setStatus(Messages.format("status.download.finished", file.getName()));
        Platform.runLater(() -> {
            episodeList.refresh();
            Feed selected = feedList.getSelectionModel().getSelectedItem();
            if (selected != null) {
                loadEpisodes(selected);
            }
        });
    }

    @Override
    public void onError(long mediaId, Exception e) {
        downloadProgress.remove(mediaId);
        setStatus(EpisodeDownloader.isCancellation(e)
                ? Messages.get("status.download.cancelled")
                : Messages.format("status.download.failed", e.getMessage()));
        Platform.runLater(episodeList::refresh);
    }

    private void updateNowPlayingArt(FeedMedia current) {
        if (nowPlayingArt == null) {
            return;
        }
        String artUrl = nowPlayingArtUrl(current);
        setArtColumnWidth(ART_COLUMN_WIDTH);
        if (artUrl == null || artUrl.isEmpty()) {
            nowPlayingArt.setUserData(null);
            nowPlayingArt.setImage(null);
            nowPlayingArt.setVisible(false);
            if (artPlaceholder != null) {
                artPlaceholder.setVisible(true);
            }
            updateSeekAccent(null);
            return;
        }
        if (!artUrl.equals(nowPlayingArt.getUserData())) {
            nowPlayingArt.setUserData(artUrl);
            nowPlayingArt.setImage(ImageCache.get(artUrl, 96, 96));
        }
        nowPlayingArt.setVisible(true);
        if (artPlaceholder != null) {
            artPlaceholder.setVisible(false);
        }
        updateSeekAccent(artUrl);
    }

    /** The episode's own image, or the subscription's when the episode brings none. */
    private String nowPlayingArtUrl(FeedMedia current) {
        if (current == null || current.getItem() == null) {
            return null;
        }
        String artUrl = current.getItem().getImageUrl();
        if (artUrl == null || artUrl.isEmpty()) {
            artUrl = feedImageUrl(current.getItem().getFeedId());
        }
        return artUrl;
    }

    /**
     * Keeps the system media card on the playing episode: metadata goes out on episode switches,
     * status on every state change, and artwork follows once its download lands. The card itself
     * is updated on its own thread, so this never waits for Windows.
     */
    private void updateSmtcCard(FeedMedia current) {
        if (current == null || current.getItem() == null) {
            smtcMediaId = -1;
            smtcArtworkFile = null;
            smtc.setStatus(false, false);
            return;
        }
        String title = current.getItem().getTitle();
        String artist = smtcArtist(current);
        String album = smtcAlbum(current);
        if (current.getId() != smtcMediaId) {
            smtcMediaId = current.getId();
            smtcArtworkFile = null;
            smtc.setEpisode(title, artist, album, null);
            fetchSmtcArtwork(current, title, artist, album, nowPlayingArtUrl(current));
        }
        smtc.setStatus(true, playback.isPlaying());
    }

    private String smtcArtist(FeedMedia media) {
        // The card's subtitle: always the subscription name, so the episode's origin is visible.
        return smtcFeedTitle(media);
    }

    private String smtcAlbum(FeedMedia media) {
        return smtcFeedTitle(media);
    }

    /** Subscription name for the card, with a lookup fallback when the item carries no feed. */
    private String smtcFeedTitle(FeedMedia media) {
        if (media == null) {
            return "";
        }
        FeedItem item = media.getItem();
        if (item != null) {
            if (item.getFeed() != null && item.getFeed().getTitle() != null
                    && !item.getFeed().getTitle().isBlank()) {
                return item.getFeed().getTitle();
            }
            String viaMedia = media.getFeedTitle();
            if (viaMedia != null && !viaMedia.isBlank()) {
                return viaMedia;
            }
            long feedId = item.getFeedId();
            if (feedId != 0 && feeds != null) {
                for (Feed feed : feeds) {
                    if (feed != null && feed.getId() == feedId
                            && feed.getTitle() != null && !feed.getTitle().isBlank()) {
                        return feed.getTitle();
                    }
                }
            }
        }
        String fallback = media.getFeedTitle();
        return fallback != null ? fallback : "";
    }

    /** Artwork for the card, downloaded in the background: the shell only reads local files. */
    private void fetchSmtcArtwork(FeedMedia media, String title, String artist, String album,
            String artUrl) {
        if (artUrl == null || artUrl.isEmpty() || background == null) {
            return;
        }
        long mediaId = media.getId();
        background.submit(() -> {
            try {
                File art = SmtcArtwork.fetch(artUrl,
                        new File(DesktopPreferences.getCacheDir(), "smtc-art"), mediaId,
                        () -> smtcMediaId == mediaId);
                if (art == null || smtcMediaId != mediaId) {
                    return;
                }
                smtcArtworkFile = art;
                // checked again on the card's thread: a switch to another episode can be
                // queued there between the check above and this update
                smtc.setEpisode(title, artist, album, art, () -> smtcMediaId == mediaId);
            } catch (Exception e) {
                // the card simply shows without artwork
            }
        });
    }

    /**
     * Draws the artwork of whatever is playing into the middle of the window icon, which is the
     * icon Windows shows on the taskbar button. Goes back to the plain app icon when there is no
     * artwork to draw, or nothing is playing.
     */
    private void updateTaskbarIcon(String artUrl) {
        if (mainStage == null || !TaskbarIcon.isEnabled()) {
            return;
        }
        String wanted = artUrl != null ? artUrl : "";
        if (wanted.equals(taskbarIconArtUrl)) {
            return;
        }
        taskbarIconArtUrl = wanted;
        if (wanted.isEmpty()) {
            mainStage.getIcons().setAll(baseIcons);
            return;
        }
        Image artwork = ImageCache.get(wanted, TaskbarIcon.ARTWORK_SIZE, TaskbarIcon.ARTWORK_SIZE);
        if (artwork == null || artwork.isError()) {
            mainStage.getIcons().setAll(baseIcons);
            return;
        }
        if (artwork.getProgress() < 1) {
            // still loading: keep the icon that is up and draw this one once the image is there
            artwork.progressProperty().addListener((obs, oldProgress, progress) -> {
                if (progress.doubleValue() < 1 || !wanted.equals(taskbarIconArtUrl)) {
                    return;
                }
                if (artwork.isError()) {
                    // a failed load also ends at 1: drop the previous episode's artwork, which
                    // was left up while this one loaded
                    mainStage.getIcons().setAll(baseIcons);
                } else {
                    applyTaskbarIcon(artwork);
                }
            });
            return;
        }
        applyTaskbarIcon(artwork);
    }

    private void applyTaskbarIcon(Image artwork) {
        java.awt.image.BufferedImage source = TaskbarIcon.toAwt(artwork);
        if (source == null) {
            mainStage.getIcons().setAll(baseIcons);
            return;
        }
        if (baseIconImages.isEmpty()) {
            for (Image icon : baseIcons) {
                java.awt.image.BufferedImage converted = TaskbarIcon.toAwt(icon);
                if (converted != null) {
                    baseIconImages.add(converted);
                }
            }
        }
        List<Image> icons = new ArrayList<>();
        for (java.awt.image.BufferedImage base : baseIconImages) {
            Image composed = TaskbarIcon.toFx(TaskbarIcon.compose(base, source));
            if (composed != null) {
                icons.add(composed);
            }
        }
        if (!icons.isEmpty()) {
            mainStage.getIcons().setAll(icons);
        }
    }

    private void setArtColumnWidth(double width) {
        if (artColumn == null) {
            return;
        }
        artColumn.setMinWidth(width);
        artColumn.setPrefWidth(width);
        artColumn.setMaxWidth(width);
    }

    private String feedImageUrl(long feedId) {
        if (feedId == 0) {
            return null;
        }
        for (Feed feed : feeds) {
            if (feed.getId() == feedId) {
                return feed.getImageUrl();
            }
        }
        return null;
    }

    private void updatePlayPauseButton() {
        if (playPauseButton != null) {
            playPauseButton.setGraphic(Icons.accent(playback != null && playback.isPlaying()
                    ? Icons.pause(26) : Icons.play(26)));
        }
    }

    /**
     * Paints the volume slider's fill in a strong neutral, close to the text colour, so the
     * level reads at a glance while the accent stays with the seek bar.
     */
    private void paintVolumeTrack() {
        if (volumeSlider == null) {
            return;
        }
        if (volumeTrack == null) {
            volumeTrack = volumeSlider.lookup(".track");
        }
        if (volumeTrack == null) {
            return;
        }
        double percent = clampTrackPercent(volumeSlider.getValue() * 100.0);
        boolean dark = ThemeManager.isDark();
        if (Math.abs(percent - paintedVolumePercent) < 1 && dark == paintedVolumeDark) {
            return;
        }
        paintedVolumePercent = percent;
        paintedVolumeDark = dark;
        String fill = dark ? "#d6d6d6" : "#505050";
        String rest = dark ? "#5f5f5f" : "#c9c9c9";
        volumeTrack.setStyle(String.format(Locale.US,
                "-fx-background-color: linear-gradient(to right, %s 0%%, %s %.2f%%,"
                        + " %s %.2f%%, %s 100%%);",
                fill, fill, percent, rest, percent, rest));
    }

    @Override
    public void onStateChanged() {
        updatePlayPauseButton();
        updateTransportEnabled();
        paintVolumeTrack();
        FeedMedia current = playback.getCurrentMedia();
        cellCurrentMediaId = current != null ? current.getId() : -1;
        cellPlaying = playback.isPlaying();
        windowsTaskbar.setPlaybackState(current != null, cellPlaying);
        updateSmtcCard(current);
        if (current == null) {
            updateBufferBar(0, 0);
        } else if (playback.isPlayingFromFile()) {
            // already on disk, so all of it can be played without the network
            int durationMs = playback.getDuration();
            updateBufferBar(durationMs, durationMs);
        }
        if (current != null && current.getItem() != null && current.getItem().getFeedId() != 0) {
            markFeedPlayed(current.getItem().getFeedId());
        }
        String title = null;
        if (current != null && current.getItem() != null) {
            title = current.getItem().getTitle();
            nowPlayingLabel.setText(title);
            nowPlayingLabel.setTooltip(title != null ? new Tooltip(title) : null);
        } else {
            nowPlayingLabel.setText(Messages.get("player.nothing_playing"));
            nowPlayingLabel.setTooltip(null);
        }
        updateNowPlayingArt(current);
        updateTaskbarIcon(nowPlayingArtUrl(current));
        updatePlayPauseButton();
        if (trayActive) {
            trayManager.update(playback.isPlaying(), title, smtcFeedTitle(current),
                    nowPlayingArt != null ? nowPlayingArt.getImage() : null);
        }
        episodeList.refresh();
        refreshFeedCounts();
        scrollToCurrentEpisode();
    }

    /**
     * Brings the playing episode into the middle of the open list, with episodes above and
     * below it (499 – [500] – 501), instead of the top edge. Selection is left alone: this
     * only scrolls, so a feed with hundreds of episodes opens on the one that matters.
     */
    private void scrollToCurrentEpisode() {
        if (playback == null || episodeList == null) {
            return;
        }
        FeedMedia current = playback.getCurrentMedia();
        if (current == null || current.getId() == lastScrolledMediaId) {
            return;
        }
        int index = indexOfMedia(visibleEpisodes, current.getId());
        if (index < 0) {
            return;
        }
        episodeList.scrollTo(centeredScrollTarget(index, estimateVisibleRows()));
        lastScrolledMediaId = current.getId();
    }

    /**
     * Opening a subscription brings back where you left off in it, every time it is opened: the
     * playing episode when it is in this feed, otherwise the one played most recently. A feed
     * never played from stays at the top.
     */
    private void scrollToLeftOff() {
        if (episodeList == null) {
            return;
        }
        FeedMedia current = playback != null ? playback.getCurrentMedia() : null;
        int index = current != null ? indexOfMedia(visibleEpisodes, current.getId()) : -1;
        if (index >= 0) {
            // counts as this episode's one scroll, so a pause or resume right after leaves it be
            lastScrolledMediaId = current.getId();
        } else {
            index = indexOfLastPlayed(visibleEpisodes);
        }
        if (index >= 0) {
            episodeList.scrollTo(centeredScrollTarget(index, estimateVisibleRows()));
        }
    }

    /** Row of the episode played most recently, or -1 when none of them has been played. */
    static int indexOfLastPlayed(List<FeedItem> items) {
        if (items == null) {
            return -1;
        }
        int found = -1;
        long latest = 0;
        for (int i = 0; i < items.size(); i++) {
            FeedItem item = items.get(i);
            if (item == null || item.getMedia() == null) {
                continue;
            }
            FeedMedia media = item.getMedia();
            Date history = media.getLastPlayedTimeHistory();
            long playedAt = Math.max(media.getLastPlayedTimeStatistics(),
                    history != null ? history.getTime() : 0);
            if (playedAt > latest) {
                latest = playedAt;
                found = i;
            }
        }
        return found;
    }

    /**
     * The row to put at the top so the playing row lands mid-list: half a page above it,
     * clamped to the top for the first episodes.
     */
    static int centeredScrollTarget(int index, int visibleRows) {
        return Math.max(0, index - Math.max(1, visibleRows / 2));
    }

    /** How many episode rows fit on screen, measured off a real cell when one is laid out. */
    private int estimateVisibleRows() {
        double height = episodeList.getHeight();
        if (height <= 0) {
            return 6;
        }
        double cellHeight = 64;
        for (Node cell : episodeList.lookupAll(".list-cell")) {
            double cellH = cell.getBoundsInParent().getHeight();
            if (cellH > 8) {
                cellHeight = cellH;
                break;
            }
        }
        return Math.max(1, (int) (height / cellHeight));
    }

    /** Row of the media in the list, or -1 when it is filtered out or plays from elsewhere. */
    static int indexOfMedia(List<FeedItem> items, long mediaId) {
        if (items == null) {
            return -1;
        }
        for (int i = 0; i < items.size(); i++) {
            FeedItem item = items.get(i);
            if (item != null && item.getMedia() != null && item.getMedia().getId() == mediaId) {
                return i;
            }
        }
        return -1;
    }

    @Override
    public void onLoadingChanged(boolean loading) {
        FeedMedia current = playback.getCurrentMedia();
        loadingMediaId = loading && current != null ? current.getId() : -1;
        episodeList.refresh();
        updateLoadingIndicator();
    }

    @Override
    public void onError(String message) {
        loadingMediaId = -1;
        setStatus(message);
        updateLoadingIndicator();
        episodeList.refresh();
        refreshFeedCounts();
    }

    /**
     * How much of the playing episode is on disk, reported by the cache as it downloads. JavaFX's
     * own {@code bufferProgressTime} is no use here: for these streams it reports zero once and
     * then never changes, so what the media engine has buffered simply is not observable. What the
     * cache has fetched is, and it answers the same question — how much will play without the
     * network.
     */
    private void onCacheProgress(long mediaId, long bytesRead, long totalBytes) {
        FeedMedia current = playback.getCurrentMedia();
        if (current == null || current.getId() != mediaId || totalBytes <= 0) {
            return;
        }
        double fraction = Math.max(0, Math.min(1, bytesRead / (double) totalBytes));
        int durationMs = playback.getDuration();
        Platform.runLater(() -> updateBufferBar((int) (fraction * durationMs), durationMs));
    }

    /**
     * Draws the seek bar's own track in three runs, the way a video player does: what played so
     * far in the theme accent, then what is fetched and can play without waiting for the network,
     * then the rest.
     */
    private void updateBufferBar(int bufferedMs, int durationMs) {
        lastBufferedMs = Math.max(bufferedMs, 0);
        paintSeekTrack((int) seekSlider.getValue(), lastBufferedMs, durationMs);
    }

    /** Repositions the progress fill as playback moves; the fetched run is kept from the cache. */
    private void updateProgressBar(int positionMs, int durationMs) {
        if (seekSlider == null) {
            return;
        }
        paintSeekTrack(positionMs, lastBufferedMs, durationMs);
    }

    /**
     * Follows the artwork of what is playing: the episode's own image, or the subscription's
     * when the episode brings none. The dominant color becomes the seek bar's accent; with no
     * artwork the slider keeps the theme's own blue, so a null or empty url clears the tint.
     */
    private void updateSeekAccent(String artUrl) {
        String wanted = artUrl != null ? artUrl : "";
        if (wanted.equals(seekAccentUrl)) {
            if (wanted.isEmpty() || seekAccent != null) {
                return;
            }
            // same artwork but still untinted: the image may have finished loading since
            // the last try, so fall through and sample it again
        } else {
            // a new episode or subscription: drop the old tint first, so its color never
            // lingers on artwork that has none, is still loading, or cannot be sampled
            seekAccentUrl = wanted;
            clearSeekAccent();
            if (wanted.isEmpty()) {
                return;
            }
        }
        Image artwork = nowPlayingArt != null ? nowPlayingArt.getImage() : null;
        if (artwork == null) {
            artwork = ImageCache.get(wanted, 96, 96);
        }
        if (artwork == null || artwork.isError()) {
            clearSeekAccent();
            return;
        }
        if (artwork.getProgress() < 1) {
            // still loading: tint once the pixels are there, unless the episode changed meanwhile.
            // Every state change comes back here while it loads; one listener per image is enough.
            if (artwork == seekAccentPendingImage) {
                return;
            }
            Image pending = artwork;
            seekAccentPendingImage = pending;
            pending.progressProperty().addListener((obs, oldProgress, progress) -> {
                if (progress.doubleValue() >= 1 && wanted.equals(seekAccentUrl)) {
                    if (pending.isError()) {
                        clearSeekAccent();
                        return;
                    }
                    String color = SeekAccent.fromFx(pending);
                    if (color != null) {
                        applySeekAccent(wanted, color);
                    } else {
                        clearSeekAccent();
                    }
                }
            });
            return;
        }
        String color = SeekAccent.fromFx(artwork);
        if (color != null) {
            applySeekAccent(wanted, color);
        } else {
            clearSeekAccent();
        }
    }

    /**
     * Drops the artwork tint from the thumb and the track, back to the theme blue. Only
     * repaints when something was tinted, so repeated clears stay cheap.
     */
    private void clearSeekAccent() {
        boolean tinted = seekAccent != null || !Objects.equals(paintedAccent, "-fx-accent");
        seekAccent = null;
        styleSeekThumb();
        paintedVolumePercent = -1;
        paintVolumeTrack();
        if (!tinted || seekSlider == null) {
            paintedPlayedPercent = -1;
            paintedBufferedPercent = -1;
            paintedAccent = null;
            paintedOutline = null;
            return;
        }
        paintedPlayedPercent = -1;
        paintedBufferedPercent = -1;
        paintedAccent = null;
        paintedOutline = null;
        paintSeekTrack((int) seekSlider.getValue(), lastBufferedMs, (int) seekSlider.getMax());
    }

    private void applySeekAccent(String wanted, String color) {
        if (!wanted.equals(seekAccentUrl) || color.equals(seekAccent)) {
            return;
        }
        seekAccent = color;
        styleSeekThumb();
        paintedVolumePercent = -1;
        paintVolumeTrack();
        paintedPlayedPercent = -1;
        paintedBufferedPercent = -1;
        if (seekSlider != null) {
            paintSeekTrack((int) seekSlider.getValue(), lastBufferedMs, (int) seekSlider.getMax());
        }
    }

    /**
     * Tints the thumb dot with the artwork accent; clears it back to the theme blue. A flat
     * fill loses the depth Modena's layered thumb brings, so the tinted dot gets its own
     * contrasting ring and drop shadow to read on any track behind it, in either theme:
     * a dark ring for a near-white fill in the light theme, a white ring otherwise.
     */
    private void styleSeekThumb() {
        if (seekSlider == null) {
            return;
        }
        if (seekThumb == null) {
            seekThumb = seekSlider.lookup(".thumb");
        }
        if (seekThumb == null) {
            return;
        }
        if (seekAccent != null) {
            boolean dark = ThemeManager.isDark();
            String ring = SeekAccent.LIGHT_THEME_OUTLINE.equals(SeekAccent.outlineFor(dark, seekAccent))
                    ? "rgba(30,30,30,0.9)" : "rgba(255,255,255,0.95)";
            seekThumb.setStyle("-fx-background-color: " + ring + ", " + seekAccent + ";"
                    + " -fx-background-insets: 0, 1.5;"
                    + " -fx-background-radius: 1em;"
                    + " -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.55), 5, 0.3, 0, 1);");
        } else {
            seekThumb.setStyle(null);
        }
    }

    private void paintSeekTrack(int positionMs, int bufferedMs, int durationMs) {
        if (seekSlider == null) {
            return;
        }
        if (seekTrack == null) {
            seekTrack = seekSlider.lookup(".track");
        }
        if (seekTrack == null) {
            return;
        }
        if (durationMs <= 0) {
            // back to whatever the stylesheet says
            seekTrack.setStyle(null);
            paintedPlayedPercent = -1;
            paintedBufferedPercent = -1;
            paintedAccent = null;
            paintedOutline = null;
            return;
        }
        String accent = seekAccent != null ? seekAccent : "-fx-accent";
        boolean dark = ThemeManager.isDark();
        String outline = SeekAccent.outlineFor(dark, accent);
        double played = clampTrackPercent(positionMs * 100.0 / durationMs);
        double buffered = Math.max(clampTrackPercent(bufferedMs * 100.0 / durationMs), played);
        if (Math.abs(played - paintedPlayedPercent) < 0.5
                && Math.abs(buffered - paintedBufferedPercent) < 0.5
                && Objects.equals(accent, paintedAccent)
                && Objects.equals(outline, paintedOutline)
                && dark == paintedDark) {
            // position ticks arrive many times a second; only restyle on visible movement
            return;
        }
        paintedPlayedPercent = played;
        paintedBufferedPercent = buffered;
        paintedAccent = accent;
        paintedOutline = outline;
        paintedDark = dark;
        String fetched = dark ? "#8d8d8d" : "#9e9e9e";
        String rest = dark ? "#5f5f5f" : "#c9c9c9";
        StringBuilder style = new StringBuilder(String.format(Locale.US,
                "-fx-background-color: linear-gradient(to right, %s 0%%, %s %.2f%%,"
                        + " %s %.2f%%, %s %.2f%%, %s %.2f%%, %s 100%%);",
                accent, accent, played, fetched, played, fetched, buffered, rest, buffered, rest));
        if (outline != null) {
            // an extreme accent would melt into the theme around it, so the whole track
            // gets a contrasting hairline; it hugs the 3px track radius from the outside
            style.append("-fx-border-color: ").append(outline).append(';')
                    .append("-fx-border-width: 1;")
                    .append("-fx-border-radius: 3.5;");
        }
        seekTrack.setStyle(style.toString());
        styleSeekThumb();
    }

    private static double clampTrackPercent(double value) {
        return Math.max(0, Math.min(100, value));
    }

    @Override
    public void onPositionChanged(int positionMs, int durationMs) {
        windowsTaskbar.setProgress(positionMs, durationMs);
        smtc.setTimeline(positionMs, durationMs);
        if (sliderDragging) {
            return;
        }
        seekSlider.setMax(Math.max(durationMs, 1));
        seekSlider.setValue(Math.min(positionMs, Math.max(durationMs, 1)));
        updateProgressBar(positionMs, durationMs);
        updateGhostMarker(positionMs, durationMs);
        updateTimeLabels(positionMs, durationMs);
        long now = System.currentTimeMillis();
        if (now - lastProgressRefreshMs > 3000) {
            lastProgressRefreshMs = now;
            episodeList.refresh();
        }
        if (trayActive) {
            trayManager.updateProgress(positionMs, durationMs);
        }
        FeedMedia current = playback.getCurrentMedia();
        if (current != null && current.getItem() != null && current.getItem().getChapters() != null) {
            int index = Chapter.getAfterPosition(current.getItem().getChapters(), positionMs);
            if (index >= 0) {
                chapterLabel.setText("▸ "
                        + current.getItem().getChapters().get(index).getTitle());
                chapterLabel.setVisible(true);
                chapterLabel.setManaged(true);
                return;
            }
        }
        chapterLabel.setText("");
        chapterLabel.setVisible(false);
        chapterLabel.setManaged(false);
    }

    /** Reads the synced position off the JavaFX thread, once, when the episode changes. */
    private void loadSyncedMarker(long itemId) {
        syncedMarkerItemId = itemId;
        syncedMarkerPositionMs = -1;
        background.submit(() -> {
            try {
                int position = database.getSyncedPosition(itemId);
                if (syncedMarkerItemId == itemId) {
                    syncedMarkerPositionMs = position;
                }
            } catch (Exception e) {
                // no marker for this episode then
            }
        });
    }

    /**
     * Re-reads the playing episode's synced position after a sync, which may have brought one
     * from another device: the per-episode lookup above would otherwise keep the old value for
     * the rest of the episode. A changed position is shown again even if the old one had faded.
     */
    private void reloadSyncedMarker() {
        long itemId = syncedMarkerItemId;
        if (itemId < 0) {
            return;
        }
        background.submit(() -> {
            try {
                int position = database.getSyncedPosition(itemId);
                Platform.runLater(() -> {
                    if (syncedMarkerItemId != itemId || position == syncedMarkerPositionMs) {
                        return;
                    }
                    syncedMarkerPositionMs = position;
                    ghostFadedItemId = -1;
                    updateGhostMarker(playback.getPosition(), playback.getDuration());
                });
            } catch (Exception e) {
                // keep the marker as it was
            }
        });
    }

    private void updateGhostMarker(int positionMs, int durationMs) {
        if (ghostMarker == null) {
            return;
        }
        FeedMedia current = playback.getCurrentMedia();
        if (current == null || durationMs <= 0 || current.getItem() == null) {
            hideGhostMarker();
            return;
        }
        if (current.getItem().getId() != syncedMarkerItemId) {
            loadSyncedMarker(current.getItem().getId());
        }
        try {
            int syncedPosition = syncedMarkerPositionMs;
            if (syncedPosition < 0 || syncedPosition > durationMs) {
                hideGhostMarker();
                return;
            }
            if (Math.abs(syncedPosition - positionMs) < SYNCED_MARKER_MIN_GAP_MS) {
                hideGhostMarker();
                return;
            }
            double trackWidth = seekSlider.getWidth() - seekSlider.getPadding().getLeft()
                    - seekSlider.getPadding().getRight();
            if (trackWidth <= 0) {
                hideGhostMarker();
                return;
            }
            double fraction = syncedPosition / (double) durationMs;
            double thumbCenter = (SLIDER_THUMB_DIAMETER / 2)
                    + fraction * (trackWidth - SLIDER_THUMB_DIAMETER);
            ghostMarker.setTranslateX(seekSlider.getPadding().getLeft()
                    + thumbCenter - (SYNCED_MARKER_SIZE / 2));
            long itemId = current.getItem().getId();
            if (itemId == ghostFadedItemId) {
                // already informed for this episode; stay out of the way
                return;
            }
            if (!ghostMarker.isVisible() || ghostFadeItemId != itemId) {
                armGhostFade(itemId);
            }
            ghostMarker.setVisible(true);
        } catch (Exception e) {
            hideGhostMarker();
        }
    }

    /**
     * Hides the synced marker and drops any fade in flight, restoring full opacity so the
     * next appearance starts solid.
     */
    private void hideGhostMarker() {
        if (ghostFadeOut != null) {
            ghostFadeOut.stop();
        }
        ghostFadeItemId = -1;
        ghostMarker.setOpacity(1);
        ghostMarker.setVisible(false);
    }

    /** Shows the marker, then dissolves it away progressively over half a minute. */
    private void armGhostFade(long itemId) {
        if (ghostFadeOut != null) {
            ghostFadeOut.stop();
        }
        ghostFadeItemId = itemId;
        ghostMarker.setOpacity(1);
        ghostFadeOut = new FadeTransition(
                Duration.seconds(SYNCED_MARKER_FADE_SECONDS), ghostMarker);
        ghostFadeOut.setFromValue(1);
        ghostFadeOut.setToValue(0);
        ghostFadeOut.setOnFinished(done -> {
            ghostFadedItemId = ghostFadeItemId;
            ghostFadeItemId = -1;
            ghostMarker.setVisible(false);
        });
        ghostFadeOut.play();
    }

    private int syncedPositionOf(FeedItem item) {
        return syncedPositions.getOrDefault(item.getId(), -1);
    }

    /** Takes a long: library-wide statistics pass 596 hours, where an int of millis wraps. */
    private static String formatDuration(long millis) {
        long totalSeconds = Math.max(millis / 1000, 0);
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;
        if (hours > 0) {
            return String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds);
        }
        return String.format(Locale.US, "%d:%02d", minutes, seconds);
    }

    @Override
    public void stop() {
        shutdown();
    }

    private void shutdown() {
        if (shuttingDown) {
            return;
        }
        shuttingDown = true;
        trayActive = false;
        try {
            if (autoRefreshTask != null) {
                autoRefreshTask.cancel(false);
            }
            if (autoRefreshScheduler != null) {
                autoRefreshScheduler.shutdownNow();
            }
        } catch (Exception e) {
            // ignore
        }
        try {
            trayManager.remove();
        } catch (Exception e) {
            // ignore
        }
        try {
            windowsTaskbar.shutdown();
        } catch (Exception e) {
            // ignore
        }
        try {
            smtc.shutdown();
        } catch (Exception e) {
            // ignore
        }
        try {
            if (mediaKeys != null) {
                mediaKeys.stop();
            }
        } catch (Exception e) {
            // ignore
        }
        try {
            sleepTimer.shutdown();
        } catch (Exception e) {
            // ignore
        }
        try {
            if (autoSyncScheduler != null) {
                autoSyncScheduler.shutdownNow();
            }
        } catch (Exception e) {
            // ignore
        }
        try {
            // quitting stops the TV too, and closes the port it fetched from
            if (castSession != null) {
                castSession.close();
            }
            if (castServer != null) {
                castServer.close();
            }
        } catch (Exception e) {
            // ignore
        }
        try {
            playback.shutdown();
        } catch (Exception e) {
            // ignore
        }
        // a second instance exits from start() before any of these exist
        if (downloader != null) {
            downloader.shutdown();
        }
        if (episodeCache != null) {
            episodeCache.shutdown();
        }
        if (background != null) {
            background.shutdownNow();
        }
        if (prefsWriter != null) {
            // let a settings change made just before closing reach the database
            prefsWriter.shutdown();
            try {
                prefsWriter.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        try {
            if (database != null) {
                database.close();
            }
        } catch (Exception e) {
            // ignore
        }
        Platform.exit();
    }

    private class EpisodeCell extends ListCell<FeedItem> {
        /** Fits "Download" plus its icon at the default control size, with room to spare. */
        private static final double DOWNLOAD_BUTTON_WIDTH = 104;
        private final Label titleLabel = new Label();
        private final Label metaLabel = new Label();
        private final Label syncBadge = new Label(Messages.get("episodes.badge.synced"));
        private final Label newBadge = new Label(Messages.get("episodes.badge.new"));
        private final Label loadingBadge = new Label(Messages.get("episodes.badge.loading"));
        private Region progressPulse;
        private final ImageView art = new ImageView();
        private final Button playButton = iconButton(Icons.play(), Messages.get("common.play"));
        private final Button downloadButton = new Button(Messages.get("episodes.download"), Icons.download());
        private final Button queueButton = iconButton(Icons.queueAdd(), Messages.get("common.add_to_queue"));
        private final Button favoriteButton = iconButton(Icons.star(false), Messages.get("episodes.favorite"));
        private final Button infoButton = iconButton(Icons.info(), Messages.get("episodes.details"));
        private final Button playedButton = iconButton(Icons.check(), Messages.get("episodes.toggle_played"));
        private final SimpleDateFormat dateFormat = new SimpleDateFormat("d MMM yyyy", Locale.US);
        private final Tooltip titleTooltip = new Tooltip();
        private final Tooltip metaTooltip = new Tooltip();
        private final Tooltip playTooltip = new Tooltip(Messages.get("common.play"));

        EpisodeCell() {
            setPrefWidth(0);
            titleLabel.setWrapText(false);
            titleLabel.setStyle("-fx-font-weight: bold;");
            titleLabel.setMinWidth(0);
            titleLabel.setMaxWidth(Double.MAX_VALUE);
            titleLabel.setTooltip(titleTooltip);
            metaLabel.setWrapText(false);
            metaLabel.getStyleClass().add("muted-label");
            metaLabel.setMinWidth(0);
            metaLabel.setMaxWidth(Double.MAX_VALUE);
            metaLabel.setTooltip(metaTooltip);
            playButton.setTooltip(playTooltip);
            syncBadge.setStyle("-fx-background-color: -fx-accent; -fx-text-fill: white; "
                    + "-fx-background-radius: 8; -fx-padding: 1 6 1 6; -fx-font-size: 10px;");
            newBadge.getStyleClass().add("badge-new");
            loadingBadge.getStyleClass().add("badge-loading");
            Region[] fixedControls = {newBadge, loadingBadge, syncBadge, playButton, downloadButton,
                    queueButton, favoriteButton, infoButton, playedButton};
            for (Region control : fixedControls) {
                control.setMinWidth(Region.USE_PREF_SIZE);
            }
            // "Download" is wider than "Delete" — pin the width to the wider text so the
            // buttons after it never shift when a download finishes
            downloadButton.setMinWidth(DOWNLOAD_BUTTON_WIDTH);
            downloadButton.setPrefWidth(DOWNLOAD_BUTTON_WIDTH);
            downloadButton.setMaxWidth(DOWNLOAD_BUTTON_WIDTH);
            syncBadge.setTooltip(
                    new Tooltip(Messages.get("episodes.badge.synced.tooltip")));
            syncBadge.setVisible(false);
            syncBadge.setManaged(false);
            newBadge.setTooltip(new Tooltip(Messages.get("episodes.badge.new.tooltip")));
            newBadge.setVisible(false);
            newBadge.setManaged(false);
            loadingBadge.setTooltip(new Tooltip(Messages.get("episodes.badge.loading.tooltip")));
            loadingBadge.setVisible(false);
            loadingBadge.setManaged(false);
            art.setFitWidth(40);
            art.setFitHeight(40);
            art.setVisible(false);
            art.setManaged(false);
            playButton.setOnAction(event -> {
                FeedItem item = getItem();
                if (item != null) {
                    FeedMedia current = playback.getCurrentMedia();
                    if (item.getMedia() != null && current != null
                            && item.getMedia().getId() == current.getId()) {
                        togglePlayPause();
                    } else {
                        playback.play(item, playbackOrder());
                    }
                }
            });
            downloadButton.setOnAction(event -> {
                FeedItem item = getItem();
                if (item != null) {
                    downloadOrDelete(item);
                }
            });
            playedButton.setOnAction(event -> {
                FeedItem item = getItem();
                if (item != null) {
                    togglePlayed(item);
                }
            });
            queueButton.setOnAction(event -> {
                FeedItem item = getItem();
                if (item != null) {
                    enqueue(item);
                }
            });
            infoButton.setOnAction(event -> {
                FeedItem item = getItem();
                if (item != null) {
                    showEpisodeDetails(item);
                }
            });
            favoriteButton.setOnAction(event -> {
                FeedItem item = getItem();
                if (item != null) {
                    toggleFavorite(item);
                }
            });
            setOnContextMenuRequested(event -> {
                FeedItem item = getItem();
                if (item == null) {
                    return;
                }
                if (!isSelected()) {
                    getListView().getSelectionModel().clearAndSelect(getIndex());
                }
                buildEpisodeContextMenu(item).show(this, event.getScreenX(), event.getScreenY());
                event.consume();
            });
        }

        private Node buildProgressBar(FeedItem item, boolean loading) {
            FeedMedia media = item.getMedia();
            if (media == null || media.getDuration() <= 0) {
                return null;
            }
            int duration = media.getDuration();
            int position = Math.max(media.getPosition(), 0);
            int synced = syncedPositionOf(item);
            boolean hasLocal = position > 0 && position < duration;
            boolean hasSynced = synced > 0 && synced <= duration
                    && Math.abs(synced - position) > duration / 100;
            if (!hasLocal && !hasSynced && !loading) {
                return null;
            }
            Region track = new Region();
            track.getStyleClass().add("episode-progress");
            track.setMinHeight(3);
            track.setPrefHeight(3);
            track.setMaxHeight(3);
            track.setMaxWidth(Double.MAX_VALUE);
            StackPane bar = new StackPane(track);
            bar.setAlignment(Pos.CENTER_LEFT);
            bar.setMaxWidth(Double.MAX_VALUE);
            if (hasLocal) {
                Region fill = new Region();
                fill.getStyleClass().add("episode-progress-fill");
                fill.setMaxHeight(Double.MAX_VALUE);
                fill.setMaxWidth(Region.USE_PREF_SIZE);
                fill.prefWidthProperty().bind(
                        track.widthProperty().multiply(position / (double) duration));
                StackPane.setAlignment(fill, Pos.CENTER_LEFT);
                bar.getChildren().add(fill);
            }
            if (hasSynced) {
                Region ghost = new Region();
                ghost.getStyleClass().add("episode-progress-synced");
                Tooltip.install(ghost, new Tooltip(SYNCED_TIP));
                ghost.setMinSize(5, 5);
                ghost.setPrefSize(5, 5);
                ghost.setMaxSize(5, 5);
                ghost.setRotate(45);
                ghost.translateXProperty().bind(
                        track.widthProperty().multiply(synced / (double) duration).subtract(2.5));
                StackPane.setAlignment(ghost, Pos.CENTER_LEFT);
                bar.getChildren().add(ghost);
            }
            if (loading) {
                progressPulse = buildLoadingPulse(48, 3, track.widthProperty());
                bar.getChildren().add(progressPulse);
            }
            return bar;
        }

        @Override
        protected void updateItem(FeedItem item, boolean empty) {
            super.updateItem(item, empty);
            if (progressPulse != null) {
                progressPulse.translateXProperty().unbind();
                progressPulse = null;
            }
            if (empty || item == null) {
                setText(null);
                setGraphic(null);
                setAccessibleText(null);
                return;
            }
            titleLabel.setText(item.getTitle());
            titleTooltip.setText(item.getTitle() != null ? item.getTitle() : "");
            StringBuilder meta = new StringBuilder();
            if (item.getPubDate() != null) {
                meta.append(dateFormat.format(item.getPubDate()));
            }
            FeedMedia media = item.getMedia();
            if (media != null && media.getDuration() > 0) {
                if (meta.length() > 0) {
                    meta.append(" · ");
                }
                meta.append(formatDuration(media.getDuration()));
            }
            if (meta.length() > 0) {
                meta.append(" · ");
            }
            boolean isNew = item.isNew();
            if (isNew) {
                meta.append(Messages.get("episodes.meta.new"));
            } else if (item.isPlayed()) {
                meta.append(Messages.get("episodes.meta.played"));
            } else {
                meta.append(Messages.get("episodes.meta.unplayed"));
            }
            boolean downloaded = media != null && media.localFileAvailable();
            // a local folder's episode is the user's own file: nothing to download or delete
            boolean localFile = LocalFolderFeeds.isLocalMedia(media);
            downloadButton.setVisible(!localFile);
            downloadButton.setManaged(!localFile);
            if (localFile) {
                meta.append(" · ").append(Messages.get("episodes.meta.local_file"));
            } else if (downloaded) {
                meta.append(" · ").append(Messages.get("episodes.meta.downloaded"));
                downloadButton.setText(Messages.get("episodes.delete"));
                downloadButton.setGraphic(Icons.remove());
            } else if (media != null && downloader.isDownloading(media.getId())) {
                Integer percent = downloadProgress.get(media.getId());
                meta.append(" · ").append(percent != null && percent >= 0
                        ? Messages.format("downloads.meta.progress", percent)
                        : Messages.get("downloads.meta.downloading"));
                downloadButton.setText(Messages.get("episodes.cancel"));
                downloadButton.setGraphic(Icons.stop());
            } else {
                downloadButton.setText(Messages.get("episodes.download"));
                downloadButton.setGraphic(Icons.download());
            }
            downloadButton.setDisable(media == null || media.getDownloadUrl() == null);
            boolean isCurrent = media != null && media.getId() == cellCurrentMediaId;
            boolean isLoading = media != null && media.getId() == loadingMediaId;
            if (isLoading) {
                meta.append(" · ").append(Messages.get("episodes.meta.loading"));
                javafx.scene.control.ProgressIndicator spinner =
                        new javafx.scene.control.ProgressIndicator(-1);
                spinner.setPrefSize(16, 16);
                spinner.setMaxSize(16, 16);
                playButton.setGraphic(spinner);
                playButton.setDisable(true);
            } else {
                boolean playingCurrent = isCurrent && cellPlaying;
                playButton.setGraphic(playingCurrent ? Icons.pause() : Icons.play());
                playButton.setDisable(media == null);
                playTooltip.setText(playingCurrent ? Messages.get("player.pause") : Messages.get("common.play"));
            }
            if (isCurrent && !isLoading) {
                meta.append(" · ").append(cellPlaying ? Messages.get("episodes.meta.playing")
                        : Messages.get("episodes.meta.paused"));
            }
            playedButton.setGraphic(item.isPlayed() ? Icons.replay() : Icons.check());
            metaLabel.setText(meta.toString());
            // the row is a graphic, so say it whole to a screen reader: title, then its details
            setAccessibleText(item.getTitle() + ". " + meta);
            metaTooltip.setText(meta.toString());
            boolean synced = syncedItemIds.contains(item.getId());
            syncBadge.setVisible(synced);
            syncBadge.setManaged(synced);
            newBadge.setVisible(isNew);
            newBadge.setManaged(isNew);
            loadingBadge.setVisible(isLoading);
            loadingBadge.setManaged(isLoading);
            String imageUrl = item.getImageUrl();
            if (imageUrl != null && !imageUrl.isEmpty()) {
                if (!imageUrl.equals(art.getUserData())) {
                    art.setUserData(imageUrl);
                    art.setImage(ImageCache.get(imageUrl, 40, 40));
                }
                art.setVisible(true);
                art.setManaged(true);
            } else {
                art.setUserData(null);
                art.setImage(null);
                art.setVisible(false);
                art.setManaged(false);
            }
            if (item.isPlayed()) {
                titleLabel.setStyle("-fx-font-weight: normal;");
                if (!titleLabel.getStyleClass().contains("muted-label")) {
                    titleLabel.getStyleClass().add("muted-label");
                }
            } else {
                titleLabel.setStyle("-fx-font-weight: bold;");
                titleLabel.getStyleClass().remove("muted-label");
            }
            Node progress = buildProgressBar(item, isLoading);
            VBox texts = progress != null
                    ? new VBox(2, titleLabel, metaLabel, progress)
                    : new VBox(2, titleLabel, metaLabel);
            if (progress != null) {
                VBox.setMargin(progress, new Insets(3, 0, 0, 0));
            }
            texts.setMinWidth(0);
            HBox.setHgrow(texts, Priority.ALWAYS);
            favoriteButton.setGraphic(
                    Icons.star(item.isTagged(FeedItem.TAG_FAVORITE)));
            HBox row = new HBox(8, art, texts, newBadge, loadingBadge, syncBadge, playButton,
                    downloadButton, queueButton, favoriteButton, infoButton, playedButton);
            row.setPadding(new Insets(4));
            if (item.isPlayed()) {
                row.getStyleClass().add("episode-row-played");
            }
            if (isCurrent) {
                row.getStyleClass().add("episode-row-current");
            }
            setGraphic(row);
            setText(null);
        }
    }
}
