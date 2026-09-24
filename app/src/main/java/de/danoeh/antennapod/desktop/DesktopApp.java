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
    private MediaKeys mediaKeys;
    private PlaybackManager playback;
    private ExecutorService background;

    private final ObservableList<Feed> feeds = FXCollections.observableArrayList();
    private final ObservableList<FeedItem> episodes = FXCollections.observableArrayList();
    private final FilteredList<Feed> visibleFeeds = new FilteredList<>(feeds, feed -> true);
    private final FilteredList<FeedItem> visibleEpisodes = new FilteredList<>(episodes, item -> true);
    private ListView<Feed> feedList;
    private ListView<FeedItem> episodeList;
    private TextField feedFilterField;
    private TextField episodeFilterField;
    private VBox sidebar;
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
    private ComboBox<String> speedBox;
    private Button silenceButton;
    private ProgressIndicator loadingSpinner;
    private Scene scene;
    private boolean sliderDragging;
    private long lastProgressRefreshMs;
    private static final String PROJECT_URL = "https://github.com/artanvrajolli/AntennaPod-Desktop";
    /** What the synced-position marker means, shown when hovering it in the seek bar or a row. */
    private static final String SYNCED_TIP = "Synced position";
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
                        "AntennaPod Desktop is already running.");
                alert.setTitle("AntennaPod Desktop");
                alert.setHeaderText(null);
                alert.showAndWait();
                Platform.exit();
            });
            return;
        }
        database = new DesktopDatabase(DesktopPreferences.getDatabaseFile());
        feedUpdater = new FeedUpdater(database);
        downloader = new EpisodeDownloader(database);
        background = Executors.newCachedThreadPool(r -> {
            Thread thread = new Thread(r, "desktop-background");
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
                Platform.runLater(episodeList::refresh);
            }
        });
        this.syncManager = syncManager;
        scheduleAutoSync();
        sleepTimer = new SleepTimer(() -> {
            // pause only: toggling could start playback if it stopped in the meantime
            playback.pause();
            setStatus("Sleep timer expired, playback paused");
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
                setStatus("Sleep timer: stopped at the end of the episode");
            }
        });
        javafx.animation.Timeline sleepTicker = new javafx.animation.Timeline(
                new javafx.animation.KeyFrame(javafx.util.Duration.seconds(1),
                        event -> updateSleepButton()));
        sleepTicker.setCycleCount(javafx.animation.Animation.INDEFINITE);
        sleepTicker.play();

        BorderPane root = new BorderPane();
        root.setTop(buildToolbar());
        VBox feedPane = buildFeedPane();
        feedPane.setMinWidth(180);
        VBox episodePane = buildEpisodePane();
        episodePane.setMinWidth(320);
        // the divider between the two lists drags horizontally, so the subscriptions
        // can be widened or narrowed; where it is left is remembered across restarts
        SplitPane listsSplit = new SplitPane(feedPane, episodePane);
        listsSplit.setDividerPositions(DesktopPreferences.getFeedSplitPosition());
        listsSplit.getDividers().get(0).positionProperty().addListener(
                (obs, oldPosition, newPosition) ->
                        DesktopPreferences.setFeedSplitPosition(newPosition.doubleValue()));
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
        mainStage = stage;
        stage.setOnCloseRequest(event -> {
            if (trayActive && DesktopPreferences.getCloseToTray()) {
                event.consume();
                stage.hide();
                if (!DesktopPreferences.getTrayHintShown()) {
                    // closing the window keeps playing by default, so say so once rather than
                    // leaving someone to think the app vanished
                    DesktopPreferences.setTrayHintShown(true);
                    trayManager.notify(APP_NAME + " is still running",
                            "It stays in the tray so playback keeps going. "
                                    + "Right-click the tray icon for controls, or to exit.");
                }
            } else {
                shutdown();
            }
        });
        stage.show();
        // the taskbar button only exists once the window is showing
        windowsTaskbar.attach(stage, new ThumbBar.Callbacks() {
            @Override
            public void onPrevious() {
                playback.playPrevious();
            }

            @Override
            public void onPlayPause() {
                playback.togglePlayPause();
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
        startMediaKeys();
        boolean trayEnabled = !"false".equalsIgnoreCase(
                System.getProperty("antennapod.desktop.tray", "true"));
        trayActive = trayEnabled && trayManager.init(new TrayManager.Callbacks() {
            @Override
            public void onPlayPause() {
                playback.togglePlayPause();
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
                playback.skip(-DesktopPreferences.getSkipBackSec() * 1000);
            }

            @Override
            public void onSkipForward() {
                playback.skip(DesktopPreferences.getSkipForwardSec() * 1000);
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
                setStatus("Auto-refresh done: " + total + " new episodes");
                refreshFeedCounts();
                Platform.runLater(() -> {
                    Feed selected = feedList.getSelectionModel().getSelectedItem();
                    if (selected != null) {
                        loadEpisodes(selected);
                    }
                });
            } catch (Exception e) {
                setStatus("Auto-refresh failed: " + e.getMessage());
            }
        }, minutes, minutes, java.util.concurrent.TimeUnit.MINUTES);
    }

    private ToolBar buildToolbar() {
        TextField urlField = new TextField();
        urlField.setPromptText("Feed URL to subscribe…");
        urlField.setPrefWidth(280);
        Button subscribeButton = new Button("Subscribe", Icons.add());
        subscribeButton.setOnAction(event -> startSubscribe(subscribeButton, urlField.getText()));
        TextField searchField = new TextField();
        searchField.setPromptText("Search podcasts…");
        searchField.setPrefWidth(220);
        Button searchButton = new Button("Search", Icons.search());
        searchButton.setOnAction(event -> startSearch(searchButton, searchField.getText()));
        searchField.setOnAction(event -> startSearch(searchButton, searchField.getText()));
        Button refreshAllButton = new Button("Refresh all", Icons.refresh());
        refreshAllButton.setOnAction(event -> {
            setStatus("Refreshing all podcasts…");
            spinWhile(refreshAllButton, this::doRefreshAll);
        });
        Button syncButton = new Button("Sync", Icons.sync());
        this.syncButton = syncButton;
        updateSyncButtonTooltip();
        syncButton.setOnAction(event -> showSyncDialog());
        // the everyday views live one click away under Library; the occasional
        // actions under More — fourteen top-level controls was a wall of buttons
        MenuButton libraryMenu = new MenuButton("Library", Icons.queue());
        libraryMenu.getItems().addAll(
                toolbarMenuItem("Queue", Icons.queue(), this::showQueue),
                toolbarMenuItem("Favorites", Icons.favorite(), this::showFavorites),
                toolbarMenuItem("History", Icons.history(), this::showHistory),
                toolbarMenuItem("Stats", Icons.stats(), this::showStatistics));
        MenuButton moreMenu = new MenuButton("More", Icons.more());
        moreMenu.getItems().addAll(
                toolbarMenuItem("Import…", Icons.download(), this::importOpml),
                toolbarMenuItem("Export…", Icons.upload(), this::exportOpml),
                toolbarMenuItem("Settings", Icons.settings(), this::showSettings),
                toolbarMenuItem("GitHub project page", Icons.github(), this::openProjectPage));
        // inputs stay left, actions sit at the far right
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        return new ToolBar(urlField, subscribeButton, searchField, searchButton,
                spacer, refreshAllButton, syncButton, libraryMenu, moreMenu);
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
        feedFilterField.setPromptText("Search subscriptions");
        feedFilterField.textProperty().addListener((obs, oldText, newText) -> applyFeedFilter());
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
        Button refreshButton = new Button("Refresh");
        refreshButton.setOnAction(event -> {
            Feed selected = feedList.getSelectionModel().getSelectedItem();
            if (selected != null) {
                setStatus("Refreshing " + selected.getTitle() + "…");
                spinWhile(refreshButton, () -> doRefreshFeed(selected));
            }
        });
        Button settingsButton = new Button("Feed settings");
        settingsButton.setOnAction(event -> {
            Feed selected = feedList.getSelectionModel().getSelectedItem();
            if (selected != null) {
                showFeedSettings(selected);
            }
        });
        HBox buttons = new HBox(8, refreshButton, settingsButton);
        buttons.setPadding(new Insets(8));
        VBox pane = new VBox(4, new Label("Subscriptions"), feedFilterField, feedList, buttons);
        pane.setPadding(new Insets(8));
        VBox.setVgrow(feedList, Priority.ALWAYS);
        return pane;
    }

    private javafx.scene.control.ContextMenu buildFeedContextMenu(Feed feed) {
        javafx.scene.control.ContextMenu menu = new javafx.scene.control.ContextMenu();
        javafx.scene.control.MenuItem refresh =
                new javafx.scene.control.MenuItem("Refresh");
        refresh.setOnAction(event -> refreshFeed(feed));
        javafx.scene.control.MenuItem settings =
                new javafx.scene.control.MenuItem("Feed settings");
        settings.setOnAction(event -> showFeedSettings(feed));
        javafx.scene.control.MenuItem unsubscribe =
                new javafx.scene.control.MenuItem("Unsubscribe");
        unsubscribe.setStyle("-fx-text-fill: #d9534f;");
        unsubscribe.setOnAction(event -> unsubscribe(feed));
        menu.getItems().addAll(refresh, settings,
                new javafx.scene.control.SeparatorMenuItem(), unsubscribe);
        return menu;
    }

    private javafx.scene.control.ContextMenu buildEpisodeContextMenu(FeedItem item) {
        List<FeedItem> targets = actionTargets(item);
        javafx.scene.control.ContextMenu menu = new javafx.scene.control.ContextMenu();
        javafx.scene.control.MenuItem markPlayed =
                new javafx.scene.control.MenuItem("Mark played");
        markPlayed.setOnAction(event -> applyPlayedState(targets, true));
        javafx.scene.control.MenuItem markUnplayed =
                new javafx.scene.control.MenuItem("Mark unplayed");
        markUnplayed.setOnAction(event -> applyPlayedState(targets, false));
        javafx.scene.control.MenuItem addToQueue =
                new javafx.scene.control.MenuItem("Add to queue");
        addToQueue.setOnAction(event -> enqueueItems(targets));
        javafx.scene.control.MenuItem removeFromQueue =
                new javafx.scene.control.MenuItem("Remove from queue");
        removeFromQueue.setOnAction(event -> dequeueItems(targets));
        javafx.scene.control.MenuItem addFavorite =
                new javafx.scene.control.MenuItem("Add to favorites");
        addFavorite.setOnAction(event -> setFavorites(targets, true));
        javafx.scene.control.MenuItem removeFavorite =
                new javafx.scene.control.MenuItem("Remove from favorites");
        removeFavorite.setOnAction(event -> setFavorites(targets, false));
        javafx.scene.control.MenuItem download =
                new javafx.scene.control.MenuItem("Download");
        download.setOnAction(event -> enqueueDownloads(targets));
        download.setDisable(!hasDownloadable(targets));
        javafx.scene.control.MenuItem deleteDownload =
                new javafx.scene.control.MenuItem("Delete download");
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
        visibleFeeds.setPredicate(feed -> query.isEmpty() || feedSearchText(feed).contains(query));
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
        Button closeButton = iconButton(Icons.remove(), "Close panel");
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
        sidebar.setVisible(true);
        sidebar.setManaged(true);
    }

    private void showModal(String title, Node content) {
        Label modalTitle = new Label(title);
        modalTitle.getStyleClass().add("sidebar-title");
        modalTitle.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(modalTitle, Priority.ALWAYS);
        Button closeButton = iconButton(Icons.remove(), "Close");
        closeButton.getStyleClass().add("flat");
        HBox header = new HBox(8, modalTitle, closeButton);
        header.getStyleClass().add("sidebar-header");
        header.setAlignment(Pos.CENTER_LEFT);
        HBox.setMargin(closeButton, new Insets(0, 0, 0, 8));

        VBox contentBox = new VBox(content);
        contentBox.getStyleClass().add("sidebar-content");
        VBox.setVgrow(content, Priority.ALWAYS);
        ScrollPane scroller = new ScrollPane(contentBox);
        scroller.setFitToWidth(true);
        scroller.getStyleClass().add("modal-scroll");
        scroller.maxHeightProperty().bind(appShell.heightProperty().subtract(160));
        VBox.setVgrow(scroller, Priority.ALWAYS);

        VBox card = new VBox(header, scroller);
        card.getStyleClass().add("modal-card");
        card.setMinWidth(420);
        card.setPrefWidth(560);
        card.setMaxWidth(560);
        card.setMaxHeight(Region.USE_PREF_SIZE);

        Region backdrop = new Region();
        backdrop.getStyleClass().add("modal-backdrop");
        backdrop.setPickOnBounds(true);
        backdrop.addEventHandler(MouseEvent.ANY, mouseEvent -> mouseEvent.consume());

        StackPane overlay = new StackPane(backdrop, card);
        overlay.getStyleClass().add("modal-overlay");
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
                setStatus("This is a development build, so there is nothing to compare against");
            }
            return;
        }
        if (requestedByUser) {
            setStatus("Checking for updates\u2026");
        }
        try {
            UpdateChecker.Release release = UpdateChecker.fetchLatest();
            if (release == null || !UpdateChecker.isNewer(release.version, current)) {
                if (requestedByUser) {
                    setStatus("AntennaPod Desktop " + current + " is the latest version");
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
                setStatus("Could not check for updates: " + e.getMessage());
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
        Label heading = new Label("AntennaPod Desktop " + release.version);
        heading.setStyle("-fx-font-size: 15px; -fx-font-weight: bold;");
        Label installed = new Label("You have " + current);
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

        Button install = new Button(release.hasInstaller() ? "Install" : "Open release page");
        install.setDefaultButton(true);
        Button later = new Button("Later");
        Button skip = new Button("Skip this version");
        HBox buttons = new HBox(8, install, later, skip);

        VBox pane = new VBox(12, heading, installed, notes, progress, status, buttons);
        pane.setPadding(new Insets(8));
        VBox.setVgrow(notes, Priority.ALWAYS);
        showModal("Update available", pane);

        later.setOnAction(event -> closeTopModal());
        skip.setOnAction(event -> {
            DesktopPreferences.setSkippedUpdateVersion(release.version);
            closeTopModal();
            setStatus("Skipped " + release.version + "; it will not be offered again");
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
            status.setText("Downloading " + release.installerName + "\u2026");
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
                    status.setText("Starting the installer. AntennaPod Desktop will close.");
                    launchInstaller(installer);
                });
            } catch (Exception e) {
                android.util.Log.e("DesktopApp", "Update download failed", e);
                String friendly = friendlyUpdateError(e);
                Platform.runLater(() -> {
                    progress.setVisible(false);
                    progress.setManaged(false);
                    status.setText("Update failed: " + friendly
                            + " You can retry or download it from the release page instead.");
                    install.setDisable(false);
                    later.setDisable(false);
                    skip.setDisable(false);
                    install.setText("Retry");
                    install.setDefaultButton(true);
                    install.setOnAction(retry -> {
                        install.setDisable(true);
                        later.setDisable(true);
                        skip.setDisable(true);
                        progress.setVisible(true);
                        progress.setManaged(true);
                        progress.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
                        status.setText("Downloading " + release.installerName + "\u2026");
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
        Button page = new Button("Open release page");
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
            return "could not save the update file (it may be locked by an antivirus scan).";
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
            setStatus("Could not start the installer: " + e.getMessage());
            return;
        }
        trayManager.remove();
        trayActive = false;
        shutdown();
    }

    /** GitHub release bodies are Markdown; only the bits the notes actually use are converted. */
    static String releaseNotesHtml(String notes) {
        if (notes == null || notes.isBlank()) {
            return "<p><i>No release notes.</i></p>";
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
            setStatus("Opened " + PROJECT_URL);
        } catch (Exception e) {
            setStatus("Could not open the browser: " + e.getMessage());
        }
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

    private boolean acquireInstanceLock() {
        try {
            java.nio.channels.FileChannel channel = java.nio.channels.FileChannel.open(
                    new File(DesktopPreferences.getDataDir(), "antennapod.lock").toPath(),
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.WRITE);
            java.nio.channels.FileLock lock = channel.tryLock();
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
        if (!sidebar.isVisible()) {
            return;
        }
        sidebarContent.getChildren().clear();
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
                return;
            }
            titleLabel.setText(feed.getTitle() != null ? feed.getTitle() : feed.getDownloadUrl());
            int[] counts = feedCounts.get(feed.getId());
            int unplayed = counts != null ? counts[0] : 0;
            int newCount = counts != null ? counts[1] : 0;
            String unplayedText = unplayed > 0 ? unplayed + " unplayed" : "";
            countLabel.setText(unplayedText);
            boolean hasNew = newCount > 0;
            newCountBadge.setText(String.valueOf(newCount));
            newCountTooltip.setText(newCount == 1 ? "1 new episode" : newCount + " new episodes");
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
        feedTitleLabel = new Label("Select a podcast");
        feedTitleLabel.setStyle("-fx-font-size: 16px; -fx-font-weight: bold;");
        sortBox = new ComboBox<>();
        sortBox.getItems().addAll("Newest first", "Oldest first", "Shortest first",
                "Longest first", "Title A-Z");
        sortBox.setValue("Newest first");
        sortBox.setOnAction(event -> {
            if (selectedFeed != null && !sortBoxProgrammatic) {
                saveSortCode(selectedFeed, sortCode(sortBox.getValue()));
            }
        });
        Button playAllButton = new Button("Play all", Icons.play());
        playAllButton.setOnAction(event -> playAll());
        episodeFilterField = new TextField();
        episodeFilterField.setPromptText("Search episodes");
        episodeFilterField.setPrefWidth(180);
        episodeFilterField.textProperty().addListener((obs, oldText, newText) -> applyEpisodeFilter());
        HBox header = new HBox(8, feedTitleLabel, episodeFilterField, sortBox, playAllButton);
        HBox.setHgrow(feedTitleLabel, Priority.ALWAYS);
        episodeList = new ListView<>(visibleEpisodes);
        episodeList.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        episodeList.setCellFactory(list -> new EpisodeCell());
        episodeList.setOnMouseClicked(event -> {
            if (event.getClickCount() == 2) {
                FeedItem selected = episodeList.getSelectionModel().getSelectedItem();
                if (selected != null && selected.getMedia() != null) {
                    playback.play(selected, playbackOrder());
                    setStatus("Playing \"" + selected.getTitle() + "\"");
                }
            }
        });
        VBox pane = new VBox(6, header, episodeList);
        pane.setPadding(new Insets(8));
        VBox.setVgrow(episodeList, Priority.ALWAYS);
        return pane;
    }

    private void saveSortCode(Feed feed, String code) {
        background.submit(() -> {
            try {
                FeedPrefs prefs = database.getFeedPrefs(feed.getId());
                prefs.sortCode = code;
                database.saveFeedPrefs(prefs);
                loadEpisodes(feed);
            } catch (Exception e) {
                setStatus("Could not save sort order: " + e.getMessage());
            }
        });
    }

    private void playAll() {
        List<FeedItem> order = playbackOrder();
        for (FeedItem item : order) {
            if (item.getMedia() != null) {
                playback.play(item, order);
                setStatus("Playing from \"" + item.getTitle() + "\" to the end");
                return;
            }
        }
        setStatus("Nothing playable in this list");
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
        setStatus("Starting \"" + feed.getTitle() + "\"…");
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
                    setStatus("Nothing playable in this subscription");
                    return;
                }
                FeedItem first = start;
                Platform.runLater(() -> playback.play(first, items));
                setStatus("Playing \"" + first.getTitle() + "\"");
            } catch (Exception e) {
                setStatus("Could not start subscription: " + e.getMessage());
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
        visibleEpisodes.setPredicate(item -> query.isEmpty()
                || (item.getTitle() != null && item.getTitle().toLowerCase(Locale.ROOT).contains(query)));
    }

    private void showSleepTimerMenu() {
        javafx.scene.control.ContextMenu menu = new javafx.scene.control.ContextMenu();
        javafx.scene.control.MenuItem off = new javafx.scene.control.MenuItem("Off");
        off.setOnAction(event -> setSleepTimer(SleepTimer.Mode.OFF, 0));
        menu.getItems().add(off);
        for (long minutes : new long[]{5, 10, 15, 30, 45, 60}) {
            javafx.scene.control.MenuItem item = new javafx.scene.control.MenuItem(minutes + " minutes");
            item.setOnAction(event -> setSleepTimer(SleepTimer.Mode.AFTER_MINUTES, minutes));
            menu.getItems().add(item);
        }
        javafx.scene.control.MenuItem endOfEpisode =
                new javafx.scene.control.MenuItem("End of episode");
        endOfEpisode.setOnAction(event -> setSleepTimer(SleepTimer.Mode.END_OF_EPISODE, 0));
        menu.getItems().add(endOfEpisode);
        menu.show(sleepButton, javafx.geometry.Side.TOP, 0, 0);
    }

    private void setSleepTimer(SleepTimer.Mode mode, long minutes) {
        if (mode == SleepTimer.Mode.AFTER_MINUTES) {
            sleepTimer.startMinutes(minutes);
            setStatus("Sleep timer: " + minutes + " minutes");
        } else if (mode == SleepTimer.Mode.END_OF_EPISODE) {
            sleepTimer.startEndOfEpisode();
            playback.setStopAfterCurrent(true);
            setStatus("Sleep timer: end of episode");
        } else {
            sleepTimer.cancel();
            playback.setStopAfterCurrent(false);
            setStatus("Sleep timer off");
        }
        updateSleepButton();
    }

    private void updateSleepButton() {
        SleepTimer.Mode mode = sleepTimer.getMode();
        if (mode == SleepTimer.Mode.AFTER_MINUTES) {
            sleepButton.setText(formatDuration((int) sleepTimer.getRemainingMs()));
        } else if (mode == SleepTimer.Mode.END_OF_EPISODE) {
            sleepButton.setText("episode");
        } else {
            sleepButton.setText("");
        }
    }

    private static Button iconButton(javafx.scene.Node graphic, String tooltip) {
        Button button = new Button("", graphic);
        button.getStyleClass().add("icon-button");
        button.setTooltip(new Tooltip(tooltip));
        return button;
    }

    private VBox buildPlayerBar() {
        Button prevButton = iconButton(Icons.previous(), "Previous episode");
        prevButton.setOnAction(event -> playback.playPrevious());
        skipBackButton = iconButton(Icons.replay10(), "");
        skipBackButton.setOnAction(event ->
                playback.skip(-DesktopPreferences.getSkipBackSec() * 1000));
        playPauseButton = iconButton(Icons.accent(Icons.play(26)), "Play / pause");
        playPauseButton.setOnAction(event -> playback.togglePlayPause());
        skipForwardButton = iconButton(Icons.forward30(), "");
        skipForwardButton.setOnAction(event ->
                playback.skip(DesktopPreferences.getSkipForwardSec() * 1000));
        Button nextButton = iconButton(Icons.next(), "Next episode");
        nextButton.setOnAction(event -> playback.playNext());
        Button stopButton = iconButton(Icons.stop(), "Stop");
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

        nowPlayingLabel = new Label("Nothing playing");
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
        Tooltip timeTooltip = new Tooltip("Click to show remaining time");
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

        silenceButton = iconButton(Icons.wave(), "Skip silence");
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
                "Mute");
        muteButton.setOnAction(event -> toggleMute());

        sleepButton = new Button("", Icons.clock());
        sleepButton.setTooltip(new Tooltip("Sleep timer"));
        sleepButton.setOnAction(event -> showSleepTimerMenu());

        chapterLabel = new Label("");
        chapterLabel.getStyleClass().add("muted-label");
        chapterLabel.setPrefWidth(160);
        chapterLabel.setMaxWidth(160);

        chapterPrevButton = iconButton(Icons.navigateBefore(), "Previous chapter");
        chapterPrevButton.setOnAction(event -> skipChapter(false));
        chapterNextButton = iconButton(Icons.navigateAfter(), "Next chapter");
        chapterNextButton.setOnAction(event -> skipChapter(true));
        setChapterButtonsVisible(false);

        HBox scrubRow = new HBox(8, elapsedLabel, stack, totalLabel, chapterLabel,
                chapterPrevButton, chapterNextButton);
        scrubRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(stack, Priority.ALWAYS);
        scrubRow.setOnScroll(event -> {
            if (Math.abs(event.getDeltaY()) >= 20 && playback.getCurrentMedia() != null) {
                playback.skip(event.getDeltaY() > 0 ? 10000 : -10000);
                event.consume();
            }
        });

        HBox transportRow = new HBox(8, prevButton, skipBackButton, playPauseHolder,
                skipForwardButton, nextButton, stopButton);
        transportRow.setAlignment(Pos.CENTER);
        HBox extrasRow = new HBox(8, speedBox, silenceButton, muteButton, volumeSlider, sleepButton);
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

        statusLabel = new Label("Ready");
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
        return new VBox(main);
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
            setStatus("Nothing to resume");
            return;
        }
        setStatus("Resuming last episode…");
        background.submit(() -> {
            try {
                FeedMedia media = database.getMedia(mediaId);
                if (media == null) {
                    setStatus("Could not resume: episode not found");
                    return;
                }
                FeedItem item = database.getItem(media.getItemId());
                if (item == null || item.getMedia() == null) {
                    setStatus("Could not resume: episode not found");
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
                setStatus("Could not resume: " + e.getMessage());
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
                    new Tooltip("Rewind " + DesktopPreferences.getSkipBackSec() + " s"));
        }
        if (skipForwardButton != null) {
            skipForwardButton.setTooltip(
                    new Tooltip("Forward " + DesktopPreferences.getSkipForwardSec() + " s"));
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

    private void handleGlobalKey(KeyEvent event) {
        if (hasModal()) {
            return;
        }
        Node focusOwner = scene != null ? scene.getFocusOwner() : null;
        if (focusOwner instanceof TextInputControl || focusOwner instanceof WebView) {
            return;
        }
        switch (event.getCode()) {
            case SPACE:
                playback.togglePlayPause();
                event.consume();
                break;
            case LEFT:
                if (event.isControlDown()) {
                    playback.playPrevious();
                } else {
                    playback.skip(-5000);
                }
                event.consume();
                break;
            case RIGHT:
                if (event.isControlDown()) {
                    playback.playNext();
                } else {
                    playback.skip(5000);
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
                playback.togglePlayPause();
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
    private void startMediaKeys() {
        if (!MediaKeys.isEnabled() || !DesktopPreferences.getMediaKeysEnabled()) {
            return;
        }
        mediaKeys = new MediaKeys(new MediaKeys.Callbacks() {
            @Override
            public void onPlayPause() {
                playback.togglePlayPause();
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
            String tip = message + "\n\nClick to copy";
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
                Platform.runLater(() -> {
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
                    if (keepId != null) {
                        for (Feed feed : all) {
                            if (feed.getId() == keepId) {
                                feedList.getSelectionModel().select(feed);
                                break;
                            }
                        }
                    } else if (!all.isEmpty()
                            && feedList.getSelectionModel().getSelectedItem() == null) {
                        feedList.getSelectionModel().selectFirst();
                    }
                });
            } catch (Exception e) {
                setStatus("Could not load feeds: " + e.getMessage());
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
                    // a big feed opens at the top; bring the playing episode into view instead
                    scrollToCurrentEpisode();
                });
            } catch (Exception e) {
                setStatus("Could not load episodes: " + e.getMessage());
            }
        });
    }

    private void subscribe(String url) {
        if (url.isEmpty()) {
            return;
        }
        setStatus("Subscribing to " + url + "…");
        background.submit(() -> doSubscribe(url));
    }

    private void startSubscribe(Button button, String rawUrl) {
        String url = rawUrl.trim();
        if (url.isEmpty()) {
            return;
        }
        setStatus("Subscribing to " + url + "…");
        spinWhile(button, () -> doSubscribe(url));
    }

    private void doSubscribe(String url) {
        try {
            Feed feed = feedUpdater.subscribe(url);
            setStatus("Subscribed to " + feed.getTitle());
            reloadFeeds(feed.getId());
        } catch (Exception e) {
            setStatus("Subscribe failed: " + e.getMessage());
        }
    }

    private void refreshFeed(Feed feed) {
        setStatus("Refreshing " + feed.getTitle() + "…");
        background.submit(() -> doRefreshFeed(feed));
    }

    private void doRefreshFeed(Feed feed) {
        try {
            List<FeedItem> added = feedUpdater.refresh(feed);
            autoDownloadNew(feed, added);
            setStatus("Refreshed " + feed.getTitle() + ": " + added.size() + " new episodes");
            reloadEpisodesIfShowing(feed);
            refreshFeedCounts();
        } catch (Exception e) {
            setStatus("Refresh failed: " + e.getMessage());
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
            setStatus("Auto-download failed: " + e.getMessage());
        }
    }

    private void refreshAll() {
        setStatus("Refreshing all podcasts…");
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
            setStatus("Refresh done: " + total + " new episodes"
                    + (errors > 0 ? ", " + errors + " failed" : ""));
            refreshFeedCounts();
            Platform.runLater(() -> {
                Feed selected = feedList.getSelectionModel().getSelectedItem();
                if (selected != null) {
                    loadEpisodes(selected);
                }
            });
        } catch (Exception e) {
            setStatus("Refresh failed: " + e.getMessage());
        }
    }

    private void unsubscribe(Feed feed) {
        background.submit(() -> {
            try {
                feedUpdater.unsubscribe(feed.getId());
                setStatus("Unsubscribed from " + feed.getTitle());
                Platform.runLater(() -> {
                    episodes.clear();
                    feedTitleLabel.setText("Select a podcast");
                });
                reloadFeeds(null);
            } catch (Exception e) {
                setStatus("Unsubscribe failed: " + e.getMessage());
            }
        });
    }

    private void importOpml() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Import OPML");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("OPML files", "*.opml", "*.xml"));
        File file = chooser.showOpenDialog(feedList.getScene().getWindow());
        if (file == null) {
            return;
        }
        setStatus("Importing " + file.getName() + "…");
        background.submit(() -> {
            try (java.io.Reader reader = new java.io.FileReader(file)) {
                OpmlImporter.ImportResult result =
                        new OpmlImporter(database, feedUpdater).importFromReader(reader);
                setStatus("Imported " + result.imported.size() + " feeds"
                        + (result.failed.isEmpty() ? "" : ", " + result.failed.size() + " failed"));
                reloadFeeds(null);
            } catch (Exception e) {
                setStatus("Import failed: " + e.getMessage());
            }
        });
    }

    private void exportOpml() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Export OPML");
        chooser.setInitialFileName("antennapod-subscriptions.opml");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("OPML files", "*.opml"));
        File file = chooser.showSaveDialog(feedList.getScene().getWindow());
        if (file == null) {
            return;
        }
        background.submit(() -> {
            try (java.io.Writer writer = new java.io.FileWriter(file)) {
                new OpmlImporter(database, feedUpdater).exportToWriter(writer);
                setStatus("Exported subscriptions to " + file.getName());
            } catch (Exception e) {
                setStatus("Export failed: " + e.getMessage());
            }
        });
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
                setStatus((favorite ? "Added to favorites: " : "Removed from favorites: ")
                        + episodeCountText(items));
                Platform.runLater(episodeList::refresh);
            } catch (Exception e) {
                setStatus("Could not update favorites: " + e.getMessage());
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
                            Button removeButton = new Button("Unfavorite");
                            removeButton.setOnAction(event -> background.submit(() -> {
                                try {
                                    database.setFavorite(item.getId(), false);
                                    Platform.runLater(() -> items.remove(item));
                                    Platform.runLater(episodeList::refresh);
                                } catch (Exception e) {
                                    setStatus("Could not update favorite: " + e.getMessage());
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
                    showSidebar("Favorites", pane);
                });
            } catch (Exception e) {
                setStatus("Could not load favorites: " + e.getMessage());
            }
        });
    }

    private void showFeedSettings(Feed feed) {
        background.submit(() -> {
            try {
                FeedPrefs prefs = database.getFeedPrefs(feed.getId());
                Platform.runLater(() -> showFeedSettingsDialog(feed, prefs));
            } catch (Exception e) {
                setStatus("Could not load feed settings: " + e.getMessage());
            }
        });
    }

    private void showFeedSettingsDialog(Feed feed, FeedPrefs prefs) {
        javafx.scene.layout.GridPane grid = new javafx.scene.layout.GridPane();
        grid.setHgap(8);
        grid.setVgap(8);
        grid.setPadding(new Insets(12));
        int row = 0;
        grid.add(new Label("Playback speed (0 = global):"), 0, row);
        Slider speedSlider = new Slider(0, 2.5, prefs.speed);
        speedSlider.setShowTickLabels(true);
        speedSlider.setShowTickMarks(true);
        speedSlider.setMajorTickUnit(0.5);
        Label speedValue = new Label(speedLabel(prefs.speed));
        speedSlider.valueProperty().addListener((obs, oldValue, newValue) ->
                speedValue.setText(speedLabel(newValue.floatValue())));
        grid.add(new HBox(8, speedSlider, speedValue), 1, row++);
        grid.add(new Label("Auto-download:"), 0, row);
        ComboBox<String> downloadBox = triStateBox(prefs.autoDownload);
        grid.add(downloadBox, 1, row++);
        grid.add(new Label("Auto-delete after playing:"), 0, row);
        ComboBox<String> deleteBox = triStateBox(prefs.autoDelete);
        grid.add(deleteBox, 1, row++);
        grid.add(new Label("Include filter:"), 0, row);
        TextField includeField = new TextField(prefs.includeFilter);
        grid.add(includeField, 1, row++);
        grid.add(new Label("Exclude filter:"), 0, row);
        TextField excludeField = new TextField(prefs.excludeFilter);
        grid.add(excludeField, 1, row++);
        grid.add(new Label("Min duration (minutes, -1 = off):"), 0, row);
        TextField minDurationField = new TextField(
                prefs.minDurationSec < 0 ? "-1" : String.valueOf(prefs.minDurationSec / 60));
        grid.add(minDurationField, 1, row++);
        grid.add(new Label("Episode order:"), 0, row);
        ComboBox<String> sortBox = new ComboBox<>();
        sortBox.getItems().addAll("Newest first", "Oldest first", "Shortest first",
                "Longest first", "Title A-Z");
        sortBox.setValue(sortLabel(prefs.sortCode));
        grid.add(sortBox, 1, row++);
        Runnable save = () -> background.submit(() -> {
            try {
                prefs.speed = (float) Math.round(speedSlider.getValue() * 20) / 20f;
                prefs.autoDownload = triStateValue(downloadBox.getValue());
                prefs.autoDelete = triStateValue(deleteBox.getValue());
                prefs.includeFilter = includeField.getText().trim();
                prefs.excludeFilter = excludeField.getText().trim();
                try {
                    int minutes = Integer.parseInt(minDurationField.getText().trim());
                    prefs.minDurationSec = minutes < 0 ? -1 : minutes * 60;
                } catch (NumberFormatException e) {
                    prefs.minDurationSec = -1;
                }
                prefs.sortCode = sortCode(sortBox.getValue());
                database.saveFeedPrefs(prefs);
                setStatus("Feed settings saved");
                Platform.runLater(() -> {
                    if (selectedFeed != null && selectedFeed.getId() == feed.getId()) {
                        loadEpisodes(feed);
                    }
                });
            } catch (Exception e) {
                setStatus("Could not save feed settings: " + e.getMessage());
            }
        });
        autoSave(speedSlider, save);
        autoSave(downloadBox.valueProperty(), save);
        autoSave(deleteBox.valueProperty(), save);
        autoSave(includeField, save);
        autoSave(excludeField, save);
        autoSave(minDurationField, save);
        autoSave(sortBox.valueProperty(), save);
        Label savedHint = new Label("Changes are saved automatically.");
        savedHint.setWrapText(true);
        grid.add(savedHint, 0, row, 2, 1);
        showSidebar("Feed settings: " + feed.getTitle(), new VBox(grid));
    }

    private static String speedLabel(float speed) {
        return speed <= 0 ? "global" : String.format(Locale.US, "%.2fx", speed);
    }

    /** The one way silence skipping is switched: player bar, tray or taskbar thumbnail. */
    private void setSilenceSkipping(boolean enabled) {
        playback.setSilenceSkipping(enabled);
        updateSilenceButtonTooltip();
        if (trayActive) {
            trayManager.updateSilenceSkipping(enabled);
        }
        windowsTaskbar.setSilenceSkipping(enabled);
        setStatus(enabled ? "Skip silence enabled" : "Skip silence disabled");
    }

    private void updateSilenceButtonTooltip() {
        if (silenceButton != null) {
            boolean enabled = DesktopPreferences.getSkipSilence();
            silenceButton.setTooltip(new Tooltip(enabled
                    ? "Skip silence: on" : "Skip silence: off"));
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

    private static final String THEME_LABEL_AUTO = "Auto (follow system)";
    private static final String THEME_LABEL_LIGHT = "Light";
    private static final String THEME_LABEL_DARK = "Dark";
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
        box.getItems().addAll("Use global setting", "On", "Off");
        box.setValue(value == FeedPrefs.ON ? "On" : (value == FeedPrefs.OFF ? "Off" : "Use global setting"));
        return box;
    }

    private static int triStateValue(String value) {
        return "On".equals(value) ? FeedPrefs.ON : ("Off".equals(value) ? FeedPrefs.OFF : FeedPrefs.USE_GLOBAL);
    }

    private static String sortLabel(String code) {
        if (code == null) {
            return "Newest first";
        }
        switch (code) {
            case "oldest": return "Oldest first";
            case "shortest": return "Shortest first";
            case "longest": return "Longest first";
            case "title": return "Title A-Z";
            default: return "Newest first";
        }
    }

    private static String sortCode(String label) {
        switch (label) {
            case "Oldest first": return "oldest";
            case "Shortest first": return "shortest";
            case "Longest first": return "longest";
            case "Title A-Z": return "title";
            default: return "newest";
        }
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
                    Button clearButton = new Button("Clear history");
                    clearButton.setOnAction(event -> background.submit(() -> {
                        try {
                            database.clearPlaybackHistory();
                            Platform.runLater(items::clear);
                        } catch (Exception e) {
                            setStatus("Could not clear history: " + e.getMessage());
                        }
                    }));
                    VBox pane = new VBox(8, list, clearButton);
                    pane.setPadding(new Insets(8));
                    VBox.setVgrow(list, Priority.ALWAYS);
                    showSidebar("Playback history", pane);
                });
            } catch (Exception e) {
                setStatus("Could not load history: " + e.getMessage());
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
                lines.add("Listened: " + formatDuration((int) totalPlayed)
                        + " of " + formatDuration((int) totalTime));
                lines.add("Episodes: " + totalEpisodes + " · Downloaded: " + totalDownloaded
                        + " (" + formatSize(totalSize) + ")");
                lines.add("");
                lines.add("Per podcast:");
                for (DesktopDatabase.FeedStatistics row : feeds) {
                    lines.add((row.feedTitle != null ? row.feedTitle : "?") + ": "
                            + formatDuration((int) row.playedTimeMs) + " listened, "
                            + row.episodes + " episodes, " + row.unplayed + " unplayed");
                }
                if (!months.isEmpty()) {
                    lines.add("");
                    lines.add("By month:");
                    for (DesktopDatabase.MonthlyStatistics month : months) {
                        lines.add(month.month + ": " + formatDuration((int) month.playedTimeMs));
                    }
                }
                Platform.runLater(() -> {
                    ListView<String> list = new ListView<>(FXCollections.observableArrayList(lines));
                    VBox pane = new VBox(8, list);
                    pane.setPadding(new Insets(8));
                    showSidebar("Statistics", pane);
                });
            } catch (Exception e) {
                setStatus("Could not load statistics: " + e.getMessage());
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
        grid.add(sectionLabel("Appearance"), 0, row++, 2, 1);
        grid.add(new Label("Theme:"), 0, row);
        ComboBox<String> themeBox = new ComboBox<>();
        themeBox.getItems().addAll(THEME_OPTIONS);
        themeBox.setValue(themeModeLabel(DesktopPreferences.getThemeMode()));
        grid.add(themeBox, 1, row++);
        grid.add(sectionLabel("Window"), 0, row++, 2, 1);
        javafx.scene.control.CheckBox closeToTrayBox = new javafx.scene.control.CheckBox(
                "Keep running in the system tray when the window is closed");
        closeToTrayBox.setSelected(DesktopPreferences.getCloseToTray());
        grid.add(closeToTrayBox, 0, row++, 2, 1);
        javafx.scene.control.CheckBox mediaKeysBox = new javafx.scene.control.CheckBox(
                "Let the keyboard's media keys control playback from any window");
        mediaKeysBox.setSelected(DesktopPreferences.getMediaKeysEnabled());
        mediaKeysBox.setDisable(!MediaKeys.isEnabled());
        mediaKeysBox.setTooltip(new Tooltip("Play/pause, next, previous and stop. Windows gives "
                + "each of these keys to one app at a time, so turning this off hands them back "
                + "to another player."));
        grid.add(mediaKeysBox, 0, row++, 2, 1);
        grid.add(sectionLabel("Updates"), 0, row++, 2, 1);
        Label versionLabel = new Label("Version " + appVersion());
        versionLabel.getStyleClass().add("muted-label");
        grid.add(versionLabel, 0, row++, 2, 1);
        javafx.scene.control.CheckBox updateCheckBox =
                new javafx.scene.control.CheckBox("Check for updates on startup");
        updateCheckBox.setSelected(DesktopPreferences.getUpdateCheckEnabled());
        grid.add(updateCheckBox, 0, row++, 2, 1);
        Button checkUpdatesButton = new Button("Check for updates");
        checkUpdatesButton.setOnAction(event -> {
            // a check the user asked for reports whatever it finds, and offers a skipped release
            // again, because asking for it is the point
            DesktopPreferences.setSkippedUpdateVersion("");
            background.submit(() -> checkForUpdates(true));
        });
        grid.add(checkUpdatesButton, 0, row++, 2, 1);
        tabs.getTabs().add(settingsTab("General", grid));

        // ---- Playback ------------------------------------------------------
        grid = settingsGrid();
        row = 0;
        grid.add(sectionLabel("Playback"), 0, row++, 2, 1);
        grid.add(new Label("Default speed:"), 0, row);
        ComboBox<String> settingsSpeedBox = new ComboBox<>();
        settingsSpeedBox.getItems().addAll(SPEED_OPTIONS);
        settingsSpeedBox.setValue(closestSpeed(DesktopPreferences.getPlaybackSpeed()));
        grid.add(settingsSpeedBox, 1, row++);
        grid.add(new Label("Skip intro (seconds):"), 0, row);
        TextField introField = new TextField(String.valueOf(DesktopPreferences.getSkipIntroSec()));
        grid.add(introField, 1, row++);
        grid.add(new Label("Skip ending (seconds):"), 0, row);
        TextField endingField = new TextField(String.valueOf(DesktopPreferences.getSkipEndingSec()));
        grid.add(endingField, 1, row++);
        grid.add(new Label("Skip back (seconds):"), 0, row);
        TextField skipBackField = new TextField(String.valueOf(DesktopPreferences.getSkipBackSec()));
        grid.add(skipBackField, 1, row++);
        grid.add(new Label("Skip forward (seconds):"), 0, row);
        TextField skipForwardField =
                new TextField(String.valueOf(DesktopPreferences.getSkipForwardSec()));
        grid.add(skipForwardField, 1, row++);
        grid.add(new Label("Volume boost (dB, 0 = off):"), 0, row);
        Slider boostSlider = new Slider(0, 12, DesktopPreferences.getVolumeBoostDb());
        boostSlider.setShowTickLabels(true);
        boostSlider.setMajorTickUnit(3);
        boostSlider.setSnapToTicks(true);
        grid.add(boostSlider, 1, row++);
        tabs.getTabs().add(settingsTab("Playback", grid));

        // ---- Downloads: download defaults and the episode cache ------------
        grid = settingsGrid();
        row = 0;
        grid.add(sectionLabel("Downloads"), 0, row++, 2, 1);
        javafx.scene.control.CheckBox downloadBox =
                new javafx.scene.control.CheckBox("Auto-download new episodes by default");
        downloadBox.setSelected(DesktopPreferences.getAutoDownloadDefault());
        grid.add(downloadBox, 0, row++, 2, 1);
        javafx.scene.control.CheckBox deleteBox =
                new javafx.scene.control.CheckBox("Delete episode files after playing by default");
        deleteBox.setSelected(DesktopPreferences.getAutoDeleteDefault());
        grid.add(deleteBox, 0, row++, 2, 1);
        Button openMediaButton = new Button("Open media folder");
        openMediaButton.setOnAction(event ->
                getHostServices().showDocument(DesktopPreferences.getMediaDir().toURI().toString()));
        grid.add(openMediaButton, 0, row++, 2, 1);
        grid.add(sectionLabel("Episode cache"), 0, row++, 2, 1);
        javafx.scene.control.CheckBox cacheBox =
                new javafx.scene.control.CheckBox("Cache episodes while they play");
        cacheBox.setSelected(DesktopPreferences.getEpisodeCacheEnabled());
        grid.add(cacheBox, 0, row++, 2, 1);
        javafx.scene.control.CheckBox cacheFinishBox =
                new javafx.scene.control.CheckBox("Remove episodes from the cache when finished");
        cacheFinishBox.setSelected(DesktopPreferences.getEpisodeCacheRemoveAfterFinish());
        grid.add(cacheFinishBox, 0, row++, 2, 1);
        grid.add(new Label("Cache limit (MB, 0 = no limit):"), 0, row);
        TextField cacheLimitField = new TextField(String.valueOf(DesktopPreferences.getEpisodeCacheLimitMb()));
        grid.add(cacheLimitField, 1, row++);
        grid.add(new Label("Prefetch episodes ahead (0 = off):"), 0, row);
        TextField cachePrefetchField =
                new TextField(String.valueOf(DesktopPreferences.getEpisodeCachePrefetchCount()));
        grid.add(cachePrefetchField, 1, row++);
        Label cacheUsageLabel = new Label("Checking cache…");
        grid.add(cacheUsageLabel, 0, row++, 2, 1);
        Runnable refreshCacheUsage = () -> background.submit(() -> {
            long used = episodeCache.sizeBytes();
            int limitMb = DesktopPreferences.getEpisodeCacheLimitMb();
            String limitText = limitMb > 0 ? formatSize(limitMb * 1024L * 1024L) : "no limit";
            String usage = "Using " + formatSize(used) + " of " + limitText
                    + " (" + episodeCache.count() + " episodes)";
            Platform.runLater(() -> cacheUsageLabel.setText(usage));
        });
        refreshCacheUsage.run();
        Button clearCacheButton = new Button("Clear episode cache");
        clearCacheButton.setOnAction(event -> background.submit(() -> {
            int removed = episodeCache.clear();
            setStatus("Cleared episode cache: "
                    + (removed == 1 ? "1 episode" : removed + " episodes"));
            refreshCacheUsage.run();
            Platform.runLater(episodeList::refresh);
        }));
        grid.add(clearCacheButton, 0, row++, 2, 1);
        Button openCacheButton = new Button("Open cache folder");
        openCacheButton.setOnAction(event ->
                getHostServices().showDocument(DesktopPreferences.getEpisodeCacheDir().toURI().toString()));
        grid.add(openCacheButton, 0, row++, 2, 1);
        tabs.getTabs().add(settingsTab("Downloads", grid));

        // ---- Network: refresh schedule and proxy ----------------------------
        grid = settingsGrid();
        row = 0;
        grid.add(sectionLabel("Refresh"), 0, row++, 2, 1);
        javafx.scene.control.CheckBox startupBox =
                new javafx.scene.control.CheckBox("Refresh all on startup");
        startupBox.setSelected(DesktopPreferences.getAutoRefreshStartup());
        grid.add(startupBox, 0, row++, 2, 1);
        grid.add(new Label("Auto-refresh every (minutes, 0 = off):"), 0, row);
        TextField intervalField = new TextField(String.valueOf(DesktopPreferences.getAutoRefreshMinutes()));
        grid.add(intervalField, 1, row++);
        grid.add(sectionLabel("Proxy (empty host = direct connection)"), 0, row++, 2, 1);
        grid.add(new Label("Host:"), 0, row);
        TextField proxyHost = new TextField(DesktopPreferences.getProxyHost());
        grid.add(proxyHost, 1, row++);
        grid.add(new Label("Port:"), 0, row);
        TextField proxyPort = new TextField(String.valueOf(DesktopPreferences.getProxyPort()));
        grid.add(proxyPort, 1, row++);
        grid.add(new Label("Username:"), 0, row);
        TextField proxyUser = new TextField(DesktopPreferences.getProxyUser());
        grid.add(proxyUser, 1, row++);
        grid.add(new Label("Password:"), 0, row);
        javafx.scene.control.PasswordField proxyPass = new javafx.scene.control.PasswordField();
        proxyPass.setText(DesktopPreferences.getProxyPassword());
        grid.add(proxyPass, 1, row++);
        tabs.getTabs().add(settingsTab("Network", grid));
        tabs.getTabs().add(buildLogsTab());

        Label savedLabel = new Label("Changes are saved automatically.");
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
                savedLabel.setText(
                        "Saved — speed/skip/silence/boost apply to newly started playback.");
                setStatus("Settings saved");
            } catch (Exception e) {
                savedLabel.setText("Could not save: " + e.getMessage());
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
        showModal("Settings", new VBox(tabs, savedBar));
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
                    setStatus("Log line copied to clipboard");
                }
            }
        });
        Label hint = new Label("Newest first · double-click a row to copy it");
        hint.getStyleClass().add("muted-label");
        Button copyAll = new Button("Copy all");
        copyAll.setOnAction(event -> {
            copyText(String.join("\n", items));
            setStatus("Log copied to clipboard (" + items.size() + " rows)");
        });
        HBox header = new HBox(8, hint, copyAll);
        header.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(hint, Priority.ALWAYS);
        VBox pane = new VBox(8, header, list);
        pane.setPadding(new Insets(12));
        VBox.setVgrow(list, Priority.ALWAYS);
        Tab tab = new Tab("Logs", pane);
        tab.setClosable(false);
        return tab;
    }

    /** Wraps one settings grid in a scrolling, fixed (non-closable) tab. */
    private static Tab settingsTab(String title, javafx.scene.layout.GridPane grid) {
        ScrollPane scroll = new ScrollPane(grid);
        scroll.setFitToWidth(true);
        Tab tab = new Tab(title, scroll);
        tab.setClosable(false);
        return tab;
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
        Button websiteButton = new Button("Open episode website");
        websiteButton.setDisable(item.getLink() == null || item.getLink().isEmpty());
        websiteButton.setOnAction(event -> getHostServices().showDocument(item.getLink()));
        Button transcriptButton = new Button("Transcript");
        transcriptButton.setDisable(item.getTranscriptUrl() == null || item.getTranscriptUrl().isEmpty());
        transcriptButton.setOnAction(event -> showTranscript(item));
        HBox buttons = new HBox(8, websiteButton, transcriptButton);
        buttons.setPadding(new Insets(8));
        VBox pane = new VBox(4, meta, webView, buttons);
        List<de.danoeh.antennapod.model.feed.Chapter> chapters = item.getChapters();
        if (chapters != null && !chapters.isEmpty()) {
            Label chaptersLabel = new Label("Chapters");
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
                    setText(formatDuration((int) chapter.getStart()) + " · " + chapter.getTitle());
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
        showSidebar(item.getTitle() != null ? item.getTitle() : "Episode", pane);
    }

    private void showTranscript(FeedItem item) {
        setStatus("Loading transcript…");
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
                            setText(formatDuration((int) segment.getStartTime()) + "  "
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
                    showSidebar("Transcript: " + item.getTitle(), pane);
                });
            } catch (Exception e) {
                setStatus("Could not load transcript: " + e.getMessage());
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
        providerBox.getItems().addAll("Disabled", "gPodder.net", "Nextcloud");
        String provider = DesktopPreferences.getSyncProvider();
        providerBox.setValue("nextcloud".equals(provider) ? "Nextcloud"
                : ("gpodder".equals(provider) ? "gPodder.net" : "Disabled"));
        TextField hostField = new TextField(DesktopPreferences.getSyncHost());
        hostField.setPromptText("Host, e.g. gpodder.net or cloud.example.com");
        TextField userField = new TextField(DesktopPreferences.getSyncUsername());
        userField.setPromptText("Username");
        javafx.scene.control.PasswordField passField = new javafx.scene.control.PasswordField();
        passField.setText(DesktopPreferences.getSyncPassword());
        passField.setPromptText("Password (Nextcloud: app password)");
        TextField deviceField = new TextField(DesktopPreferences.getSyncDeviceCaption());
        Label syncStatus = new Label(syncStatusText() + " — changes are saved automatically.");
        syncStatus.setWrapText(true);
        javafx.scene.control.CheckBox autoSyncBox = new javafx.scene.control.CheckBox(
                "Sync automatically (at startup and after playback)");
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
            setStatus("Sync settings saved");
        };
        autoSave(providerBox.valueProperty(), save);
        autoSave(hostField, save);
        autoSave(userField, save);
        autoSave(passField, save);
        autoSave(deviceField, save);
        autoSave(autoSyncBox.selectedProperty(), save);
        Button testButton = new Button("Test login");
        testButton.setOnAction(event -> {
            save.run();
            syncStatus.setText("Testing login…");
            Node original = showButtonSpinner(testButton);
            background.submit(() -> {
                try {
                    syncManager.testLogin();
                    Platform.runLater(() -> syncStatus.setText("Login successful"));
                } catch (Exception e) {
                    Platform.runLater(() -> syncStatus.setText("Login failed: " + e.getMessage()));
                } finally {
                    Platform.runLater(() -> hideButtonSpinner(testButton, original));
                }
            });
        });
        Button syncNowButton = new Button("Sync now");
        syncNowButton.setOnAction(event -> {
            save.run();
            Node[] original = new Node[1];
            runSync(
                    () -> {
                        original[0] = showButtonSpinner(syncNowButton);
                        syncStatus.setText("Syncing…");
                        setStatus("Syncing…");
                    },
                    message -> {
                        syncStatus.setText(message);
                        hideButtonSpinner(syncNowButton, original[0]);
                    });
        });
        Button devicesButton = new Button("Import from another device…");
        devicesButton.setOnAction(event -> {
            save.run();
            showDevicesDialog();
        });
        javafx.scene.layout.GridPane grid = new javafx.scene.layout.GridPane();
        grid.setHgap(8);
        grid.setVgap(8);
        grid.setPadding(new Insets(12));
        grid.add(new Label("Provider:"), 0, 0);
        grid.add(providerBox, 1, 0);
        grid.add(new Label("Host:"), 0, 1);
        grid.add(hostField, 1, 1);
        grid.add(new Label("Username:"), 0, 2);
        grid.add(userField, 1, 2);
        grid.add(new Label("Password:"), 0, 3);
        grid.add(passField, 1, 3);
        grid.add(new Label("Device name:"), 0, 4);
        grid.add(deviceField, 1, 4);
        grid.add(new HBox(8, testButton, syncNowButton), 0, 5, 2, 1);
        grid.add(devicesButton, 0, 6, 2, 1);
        grid.add(autoSyncBox, 0, 7, 2, 1);
        grid.add(syncStatus, 0, 8, 2, 1);
        showModal("Sync settings", new VBox(grid));
    }

    private String deviceImportHint() {
        try {
            List<de.danoeh.antennapod.net.sync.gpoddernet.model.GpodnetDevice> devices =
                    syncManager.listDevices();
            for (de.danoeh.antennapod.net.sync.gpoddernet.model.GpodnetDevice device : devices) {
                if (!DesktopPreferences.getSyncDeviceId().equals(device.getId())
                        && device.getSubscriptions() > 0) {
                    return ". Nothing changed on this device — but other devices hold subscriptions."
                            + " Use 'Import from another device…'.";
                }
            }
        } catch (Exception e) {
            // ignore hint on error
        }
        return "";
    }

    private void showDevicesDialog() {
        Label status = new Label("Loading devices…");
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
                Label title = new Label(device.getCaption() + " (" + device.getType() + ") — "
                        + device.getSubscriptions() + " subscriptions");
                title.setWrapText(true);
                Button importButton = new Button("Import subscriptions");
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
        showModal("Devices on sync account", pane);
        background.submit(() -> {
            try {
                List<de.danoeh.antennapod.net.sync.gpoddernet.model.GpodnetDevice> devices =
                        syncManager.listDevices();
                Platform.runLater(() -> {
                    list.getItems().setAll(devices);
                    status.setText(devices.isEmpty()
                            ? "No other devices found (Nextcloud has no device list)."
                            : "Select a device to import its subscriptions.");
                });
            } catch (Exception e) {
                Platform.runLater(() -> status.setText("Could not load devices: " + e.getMessage()));
            }
        });
    }

    private void importDeviceSubscriptions(
            de.danoeh.antennapod.net.sync.gpoddernet.model.GpodnetDevice device,
            Label status) {
        status.setText("Importing from " + device.getCaption() + "…");
        background.submit(() -> {
            try {
                int added = syncManager.importFromDevice(device.getId());
                Platform.runLater(() -> {
                    status.setText("Imported " + added + " subscriptions from " + device.getCaption());
                    reloadFeeds(null);
                });
                setStatus("Imported " + added + " subscriptions from " + device.getCaption());
            } catch (Exception e) {
                Platform.runLater(() -> status.setText("Import failed: " + e.getMessage()));
            }
        });
    }

    private String syncStatusText() {
        if (!DesktopPreferences.isSyncEnabled()) {
            return "Sync is disabled.";
        }
        return "Sync enabled (" + DesktopPreferences.getSyncProvider() + " as "
                + DesktopPreferences.getSyncUsername() + ")." + lastSyncSuffix();
    }

    private String syncResultText(SyncManager.SyncResult result) {
        StringBuilder message = new StringBuilder("Synced: ")
                .append(result.subscriptionsAdded).append(" subscriptions added, ")
                .append(result.actionsUploaded).append(" actions uploaded, ")
                .append(result.actionsApplied).append(" state updates applied");
        if (!result.playedItemIds.isEmpty()) {
            message.append(" · ").append(result.playedItemIds.size()).append(" marked finished");
        }
        if (!result.unplayedItemIds.isEmpty()) {
            message.append(" · ").append(result.unplayedItemIds.size())
                    .append(" marked unfinished");
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
            return " Never synced yet.";
        }
        return " Last synced "
                + new SimpleDateFormat("d MMM HH:mm", Locale.US).format(new Date(last)) + ".";
    }

    private void updateSyncButtonTooltip() {
        if (syncButton == null) {
            return;
        }
        long last = DesktopPreferences.getLastSyncTime();
        syncButton.setTooltip(new Tooltip(last <= 0 ? "Sync — never synced"
                : "Sync — last synced "
                        + new SimpleDateFormat("d MMM HH:mm", Locale.US).format(new Date(last))));
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
                onFinish.accept("Sync already running");
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
                        ? "Sync finished"
                        : "Sync finished: " + result.playedItemIds.size() + " marked finished";
                Platform.runLater(() -> {
                    syncedItemIds.clear();
                    syncedItemIds.addAll(result.syncedItemIds);
                    updateGhostMarker(playback.getPosition(), playback.getDuration());
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
                        onFinish.accept("Sync failed: " + e.getMessage());
                    }
                });
                setStatus("Sync failed: " + e.getMessage());
            } finally {
                syncRunning.set(false);
            }
        });
    }

    private void search(String query) {
        if (query.isEmpty()) {
            return;
        }
        setStatus("Searching for \"" + query + "…");
        background.submit(() -> doSearch(query));
    }

    private void startSearch(Button button, String rawQuery) {
        String query = rawQuery.trim();
        if (query.isEmpty()) {
            return;
        }
        setStatus("Searching for \"" + query + "…");
        spinWhile(button, () -> doSearch(query));
    }

    private void doSearch(String query) {
        try {
            List<PodcastSearchResult> results =
                    new CombinedSearcher().search(query).blockingGet();
            Platform.runLater(() -> showSearchResults(query, results));
            setStatus("Found " + results.size() + " results for \"" + query + "\"");
        } catch (Exception e) {
            setStatus("Search failed: " + e.getMessage());
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
                Button subscribeButton = new Button("Subscribe");
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
        showSidebar("Search results: " + query, pane);
    }

    /**
     * The podcast behind a search result: what the directory gave us straight away, and the
     * description, website and episode count once the feed itself has been fetched. Nothing is
     * stored - the feed is only read so the user can decide whether to subscribe.
     */
    private void showPodcastDetails(PodcastSearchResult result) {
        Label author = new Label(result.author == null || result.author.isEmpty()
                ? "Unknown author" : result.author);
        author.getStyleClass().add("muted-label");
        Label meta = new Label("Loading details\u2026");
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

        Button subscribeButton = new Button("Subscribe");
        subscribeButton.setDefaultButton(true);
        subscribeButton.setDisable(result.feedUrl == null || result.feedUrl.isEmpty());
        subscribeButton.setOnAction(event -> {
            closeTopModal();
            hideSidebar();
            subscribe(result.feedUrl);
        });
        markSubscribed(subscribeButton, result.feedUrl);
        Button websiteButton = new Button("Open website");
        websiteButton.setDisable(true);
        Button copyButton = new Button("Copy feed URL");
        copyButton.setDisable(result.feedUrl == null || result.feedUrl.isEmpty());
        copyButton.setOnAction(event -> {
            javafx.scene.input.ClipboardContent content = new javafx.scene.input.ClipboardContent();
            content.putString(result.feedUrl);
            javafx.scene.input.Clipboard.getSystemClipboard().setContent(content);
            setStatus("Feed URL copied");
        });
        HBox buttons = new HBox(8, subscribeButton, websiteButton, copyButton);

        WebView description = new WebView();
        description.setPrefHeight(260);
        showHtml(description, Shownotes.toPage(null, "<p>Loading description\u2026</p>",
                ThemeManager.isDark()));

        Label feedUrlLabel = new Label(result.feedUrl);
        feedUrlLabel.getStyleClass().add("muted-label");
        feedUrlLabel.setWrapText(true);

        VBox pane = new VBox(12, header, buttons, description, feedUrlLabel);
        pane.setPadding(new Insets(8));
        VBox.setVgrow(description, Priority.ALWAYS);
        showModal(result.title, pane);

        if (result.feedUrl == null || result.feedUrl.isEmpty()) {
            meta.setText("This result has no feed address.");
            showHtml(description, Shownotes.toPage(null, "", ThemeManager.isDark()));
            return;
        }
        background.submit(() -> {
            try {
                Feed feed = feedUpdater.preview(result.feedUrl);
                Platform.runLater(() -> {
                    meta.setText(describePodcast(feed));
                    String html = feed.getDescription() == null || feed.getDescription().isEmpty()
                            ? "<p><i>This podcast has no description.</i></p>" : feed.getDescription();
                    showHtml(description, Shownotes.toPage(null, html, ThemeManager.isDark()));
                    String link = feed.getLink();
                    websiteButton.setDisable(link == null || link.isEmpty());
                    websiteButton.setOnAction(event -> getHostServices().showDocument(link));
                    // the feed may redirect, so re-check against the address actually fetched
                    markSubscribed(subscribeButton, feed.getDownloadUrl());
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    meta.setText("Could not load details: " + e.getMessage());
                    showHtml(description, Shownotes.toPage(null, "", ThemeManager.isDark()));
                });
            }
        });
    }

    /** Episode count, language and the newest episode, as far as the feed says. */
    static String describePodcast(Feed feed) {
        List<String> parts = new ArrayList<>();
        int episodes = feed.getItems() == null ? 0 : feed.getItems().size();
        parts.add(episodes + (episodes == 1 ? " episode" : " episodes"));
        if (feed.getLanguage() != null && !feed.getLanguage().isEmpty()) {
            parts.add(feed.getLanguage());
        }
        Date latest = newestPubDate(feed);
        if (latest != null) {
            parts.add("latest " + new SimpleDateFormat("d MMM yyyy", Locale.US).format(latest));
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
        subscribeButton.setText("Subscribed");
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
                setStatus((played ? "Marked played: " : "Marked unplayed: ") + episodeCountText(items));
                Platform.runLater(episodeList::refresh);
                refreshFeedCounts();
            } catch (Exception e) {
                setStatus("Could not update episodes: " + e.getMessage());
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
        return count == 1 ? "1 episode" : count + " episodes";
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
                setStatus("Added to queue: " + episodeCountText(targets));
            } catch (Exception e) {
                setStatus("Could not add to queue: " + e.getMessage());
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
                setStatus("Removed from queue: " + episodeCountText(targets));
            } catch (Exception e) {
                setStatus("Could not remove from queue: " + e.getMessage());
            }
        });
    }

    private void showQueue() {
        background.submit(() -> {
            try {
                List<FeedItem> queue = database.getQueue();
                Platform.runLater(() -> showQueueDialog(queue));
            } catch (Exception e) {
                setStatus("Could not load queue: " + e.getMessage());
            }
        });
    }

    private void showQueueDialog(List<FeedItem> initialQueue) {
        ObservableList<FeedItem> queueItems = FXCollections.observableArrayList(initialQueue);
        ListView<FeedItem> queueList = new ListView<>(queueItems);
        queueList.setCellFactory(view -> fullWidthCell(new ListCell<>() {
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
                Button upButton = iconButton(Icons.up(), "Move up");
                upButton.setOnAction(event -> moveQueueItem(item, true, queueItems));
                Button downButton = iconButton(Icons.down(), "Move down");
                downButton.setOnAction(event -> moveQueueItem(item, false, queueItems));
                Button removeButton = new Button("Remove", Icons.remove());
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
        Button playAllButton = new Button("Play all");
        playAllButton.setOnAction(event -> {
            if (!queueItems.isEmpty() && queueItems.get(0).getMedia() != null) {
                playback.play(queueItems.get(0), new ArrayList<>(queueItems));
            }
        });
        Button clearButton = new Button("Clear");
        clearButton.setOnAction(event -> background.submit(() -> {
            try {
                database.clearQueue();
                Platform.runLater(queueItems::clear);
            } catch (Exception e) {
                setStatus("Could not clear queue: " + e.getMessage());
            }
        }));
        HBox buttons = new HBox(8, playAllButton, clearButton);
        buttons.setPadding(new Insets(8, 0, 0, 0));
        VBox pane = new VBox(8, queueList, buttons);
        pane.setPadding(new Insets(8));
        VBox.setVgrow(queueList, Priority.ALWAYS);
        showSidebar("Queue", pane);
    }

    private void moveQueueItem(FeedItem item, boolean up, ObservableList<FeedItem> queueItems) {
        background.submit(() -> {
            try {
                database.moveQueueItem(item.getId(), up);
                List<FeedItem> queue = database.getQueue();
                Platform.runLater(() -> queueItems.setAll(queue));
            } catch (Exception e) {
                setStatus("Could not reorder queue: " + e.getMessage());
            }
        });
    }

    private void removeQueueItem(FeedItem item, ObservableList<FeedItem> queueItems) {
        background.submit(() -> {
            try {
                database.removeFromQueue(item.getId());
                Platform.runLater(() -> queueItems.remove(item));
            } catch (Exception e) {
                setStatus("Could not remove from queue: " + e.getMessage());
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
            setStatus("Nothing to download");
            return;
        }
        setStatus("Downloading " + episodeCountText(queued));
        episodeList.refresh();
    }

    private void deleteDownloads(List<FeedItem> items) {
        List<FeedMedia> deletable = new ArrayList<>();
        for (FeedItem item : items) {
            FeedMedia media = item.getMedia();
            if (media != null && media.localFileAvailable() && media.getLocalFileUrl() != null) {
                deletable.add(media);
            }
        }
        if (deletable.isEmpty()) {
            setStatus("Nothing to delete");
            return;
        }
        background.submit(() -> {
            try {
                for (FeedMedia media : deletable) {
                    new File(media.getLocalFileUrl()).delete();
                    media.setLocalFileUrl(null);
                    database.clearMediaDownload(media.getId());
                    if (deleteCachedCopy(media)) {
                        database.setMediaCacheFile(media.getId(), null);
                    }
                }
                setStatus("Deleted downloads: "
                        + (deletable.size() == 1 ? "1 episode" : deletable.size() + " episodes"));
            } catch (Exception e) {
                setStatus("Could not delete download: " + e.getMessage());
            }
            Platform.runLater(episodeList::refresh);
        });
    }

    /** Deletes the playback cache's copy of an episode; true if there was one. */
    private static boolean deleteCachedCopy(FeedMedia media) {
        if (media.getCacheFileUrl() == null) {
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
            if (media != null && media.localFileAvailable() && media.getLocalFileUrl() != null) {
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
                FeedPrefs prefs = database.getFeedPrefs(media.getItem().getFeedId());
                if (!prefs.effectiveAutoDelete(DesktopPreferences.getAutoDeleteDefault())) {
                    return;
                }
                if (media.getLocalFileUrl() != null) {
                    new File(media.getLocalFileUrl()).delete();
                    media.setLocalFileUrl(null);
                    database.clearMediaDownload(media.getId());
                    setStatus("Auto-deleted: " + media.getHumanReadableIdentifier());
                    Platform.runLater(episodeList::refresh);
                }
            } catch (Exception e) {
                setStatus("Auto-delete failed: " + e.getMessage());
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
            setStatus("Auto-downloaded: " + item.getTitle());
            Platform.runLater(episodeList::refresh);
        }

        @Override
        public void onError(long mediaId, Exception e) {
            setStatus("Auto-download failed for " + item.getTitle() + ": " + e.getMessage());
        }
    }

    @Override
    public void onProgress(long mediaId, long bytesRead, long totalBytes) {
        int percent = totalBytes > 0 ? (int) (bytesRead * 100 / totalBytes) : -1;
        Integer previous = downloadProgress.get(mediaId);
        if (previous == null || percent < 0 || percent - previous >= 5) {
            downloadProgress.put(mediaId, percent);
            Platform.runLater(episodeList::refresh);
        }
    }

    @Override
    public void onFinished(long mediaId, File file) {
        downloadProgress.remove(mediaId);
        setStatus("Download finished: " + file.getName());
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
        setStatus("Download failed: " + e.getMessage());
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
                if (progress.doubleValue() >= 1 && !artwork.isError()
                        && wanted.equals(taskbarIconArtUrl)) {
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

    /** Paints the volume slider's fill in neutral grey, leaving the accent to the seek bar. */
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
        if (Math.abs(percent - paintedVolumePercent) < 1) {
            return;
        }
        paintedVolumePercent = percent;
        boolean dark = ThemeManager.isDark();
        String fill = dark ? "#8d8d8d" : "#9e9e9e";
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
        windowsTaskbar.setPlaybackState(current != null, playback.isPlaying());
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
            nowPlayingLabel.setText("Nothing playing");
            nowPlayingLabel.setTooltip(null);
        }
        updateNowPlayingArt(current);
        updateTaskbarIcon(nowPlayingArtUrl(current));
        updatePlayPauseButton();
        if (trayActive) {
            trayManager.update(playback.isPlaying(), title,
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
            return;
        }
        paintedPlayedPercent = -1;
        paintedBufferedPercent = -1;
        paintedAccent = null;
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
     * white ring and drop shadow to read on any track behind it, in either theme.
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
            seekThumb.setStyle("-fx-background-color: rgba(255,255,255,0.95), " + seekAccent + ";"
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
            return;
        }
        String accent = seekAccent != null ? seekAccent : "-fx-accent";
        double played = clampTrackPercent(positionMs * 100.0 / durationMs);
        double buffered = Math.max(clampTrackPercent(bufferedMs * 100.0 / durationMs), played);
        if (Math.abs(played - paintedPlayedPercent) < 0.5
                && Math.abs(buffered - paintedBufferedPercent) < 0.5
                && Objects.equals(accent, paintedAccent)) {
            // position ticks arrive many times a second; only restyle on visible movement
            return;
        }
        paintedPlayedPercent = played;
        paintedBufferedPercent = buffered;
        paintedAccent = accent;
        boolean dark = ThemeManager.isDark();
        String fetched = dark ? "#8d8d8d" : "#9e9e9e";
        String rest = dark ? "#5f5f5f" : "#c9c9c9";
        seekTrack.setStyle(String.format(Locale.US,
                "-fx-background-color: linear-gradient(to right, %s 0%%, %s %.2f%%,"
                        + " %s %.2f%%, %s %.2f%%, %s %.2f%%, %s 100%%);",
                accent, accent, played, fetched, played, fetched, buffered, rest, buffered, rest));
        styleSeekThumb();
    }

    private static double clampTrackPercent(double value) {
        return Math.max(0, Math.min(100, value));
    }

    @Override
    public void onPositionChanged(int positionMs, int durationMs) {
        windowsTaskbar.setProgress(positionMs, durationMs);
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

    private static String formatDuration(int millis) {
        int totalSeconds = Math.max(millis / 1000, 0);
        int hours = totalSeconds / 3600;
        int minutes = (totalSeconds % 3600) / 60;
        int seconds = totalSeconds % 60;
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
        private final Label syncBadge = new Label("SYNCED");
        private final Label newBadge = new Label("NEW");
        private final Label loadingBadge = new Label("LOADING");
        private Region progressPulse;
        private final ImageView art = new ImageView();
        private final Button playButton = iconButton(Icons.play(), "Play");
        private final Button downloadButton = new Button("Download", Icons.download());
        private final Button queueButton = iconButton(Icons.queueAdd(), "Add to queue");
        private final Button favoriteButton = iconButton(Icons.star(false), "Favorite");
        private final Button infoButton = iconButton(Icons.info(), "Episode details");
        private final Button playedButton = iconButton(Icons.check(), "Mark played / unplayed");
        private final SimpleDateFormat dateFormat = new SimpleDateFormat("d MMM yyyy", Locale.US);
        private final Tooltip titleTooltip = new Tooltip();
        private final Tooltip metaTooltip = new Tooltip();
        private final Tooltip playTooltip = new Tooltip("Play");

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
                    new Tooltip("Appeared in the last sync's episode actions"));
            syncBadge.setVisible(false);
            syncBadge.setManaged(false);
            newBadge.setTooltip(new Tooltip("New episode"));
            newBadge.setVisible(false);
            newBadge.setManaged(false);
            loadingBadge.setTooltip(new Tooltip("Loading media"));
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
                        playback.togglePlayPause();
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
                meta.append("New");
            } else if (item.isPlayed()) {
                meta.append("Played");
            } else {
                meta.append("Unplayed");
            }
            boolean downloaded = media != null && media.localFileAvailable();
            if (downloaded) {
                meta.append(" · Downloaded");
                downloadButton.setText("Delete");
                downloadButton.setGraphic(Icons.remove());
            } else if (media != null && downloader.isDownloading(media.getId())) {
                Integer percent = downloadProgress.get(media.getId());
                meta.append(" · Downloading")
                        .append(percent != null && percent >= 0 ? " " + percent + "%" : "…");
                downloadButton.setText("Cancel");
                downloadButton.setGraphic(Icons.stop());
            } else {
                downloadButton.setText("Download");
                downloadButton.setGraphic(Icons.download());
            }
            downloadButton.setDisable(media == null || media.getDownloadUrl() == null);
            FeedMedia current = playback.getCurrentMedia();
            boolean isCurrent = media != null && current != null && media.getId() == current.getId();
            boolean isLoading = media != null && media.getId() == loadingMediaId;
            if (isLoading) {
                meta.append(" · Loading…");
                javafx.scene.control.ProgressIndicator spinner =
                        new javafx.scene.control.ProgressIndicator(-1);
                spinner.setPrefSize(16, 16);
                spinner.setMaxSize(16, 16);
                playButton.setGraphic(spinner);
                playButton.setDisable(true);
            } else {
                boolean playingCurrent = isCurrent && playback.isPlaying();
                playButton.setGraphic(playingCurrent ? Icons.pause() : Icons.play());
                playButton.setDisable(media == null);
                playTooltip.setText(playingCurrent ? "Pause" : "Play");
            }
            if (isCurrent && !isLoading) {
                meta.append(playback.isPlaying() ? " · Playing" : " · Paused");
            }
            playedButton.setGraphic(item.isPlayed() ? Icons.replay() : Icons.check());
            metaLabel.setText(meta.toString());
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
            if (isCurrent) {
                row.getStyleClass().add("episode-row-current");
            }
            setGraphic(row);
            setText(null);
        }
    }
}
