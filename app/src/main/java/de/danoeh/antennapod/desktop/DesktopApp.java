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
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.Slider;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputControl;
import javafx.scene.control.ToolBar;
import javafx.scene.control.Tooltip;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.web.WebView;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;

public class DesktopApp extends Application implements PlaybackManager.Listener,
        EpisodeDownloader.ProgressListener {
    private DesktopDatabase database;
    private FeedUpdater feedUpdater;
    private EpisodeDownloader downloader;
    private PlaybackManager playback;
    private ExecutorService background;

    private final ObservableList<Feed> feeds = FXCollections.observableArrayList();
    private final ObservableList<FeedItem> episodes = FXCollections.observableArrayList();
    private ListView<Feed> feedList;
    private ListView<FeedItem> episodeList;
    private Label feedTitleLabel;
    private Label statusLabel;
    private Label nowPlayingLabel;
    private ImageView nowPlayingArt;
    private Label elapsedLabel;
    private Label totalLabel;
    private Button playPauseButton;
    private Button skipBackButton;
    private Button skipForwardButton;
    private Button muteButton;
    private Button chapterPrevButton;
    private Button chapterNextButton;
    private Slider seekSlider;
    private Slider volumeSlider;
    private ComboBox<String> speedBox;
    private ProgressIndicator loadingSpinner;
    private Scene scene;
    private boolean sliderDragging;
    private boolean showRemainingTime;
    private double lastVolume;
    private final Map<Long, Integer> downloadProgress = new HashMap<>();
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
    private volatile long loadingMediaId = -1;
    private final TrayManager trayManager = new TrayManager();
    private boolean trayActive;
    private Stage mainStage;
    private java.util.concurrent.ScheduledExecutorService autoRefreshScheduler;
    private java.util.concurrent.ScheduledFuture<?> autoRefreshTask;

    public static void main(String[] args) {
        launch(args);
    }

    @Override
    public void start(Stage stage) throws Exception {
        DesktopPreferences.getDataDir().mkdirs();
        DesktopPreferences.getMediaDir().mkdirs();
        DesktopPreferences.getCacheDir().mkdirs();
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
        this.syncManager = syncManager;
        scheduleAutoSync();
        sleepTimer = new SleepTimer(() -> {
            if (playback.isPlaying()) {
                playback.togglePlayPause();
            }
            setStatus("Sleep timer expired, playback paused");
        });
        sleepTimer.restore();
        if (sleepTimer.getMode() == SleepTimer.Mode.END_OF_EPISODE) {
            playback.setStopAfterCurrent(true);
        }
        javafx.animation.Timeline sleepTicker = new javafx.animation.Timeline(
                new javafx.animation.KeyFrame(javafx.util.Duration.seconds(1),
                        event -> updateSleepButton()));
        sleepTicker.setCycleCount(javafx.animation.Animation.INDEFINITE);
        sleepTicker.play();

        BorderPane root = new BorderPane();
        root.setTop(buildToolbar());
        root.setLeft(buildFeedPane());
        root.setCenter(buildEpisodePane());
        root.setBottom(buildPlayerBar());

        stage.setTitle("AntennaPod Desktop");
        scene = new Scene(root, 1100, 700);
        scene.addEventFilter(KeyEvent.KEY_PRESSED, this::handleGlobalKey);
        stage.setScene(scene);
        mainStage = stage;
        stage.setOnCloseRequest(event -> {
            if (trayActive) {
                event.consume();
                stage.hide();
                setStatus("Minimized to tray — right-click the tray icon to exit");
            } else {
                shutdown();
            }
        });
        stage.show();
        trayActive = trayManager.init(new TrayManager.Callbacks() {
            @Override
            public void onPlayPause() {
                playback.togglePlayPause();
            }

            @Override
            public void onNext() {
                playback.playNext();
            }

            @Override
            public void onShow() {
                mainStage.show();
                mainStage.toFront();
            }

            @Override
            public void onExit() {
                trayManager.remove();
                trayActive = false;
                shutdown();
            }
        });

        reloadFeeds(null);
        applyProxy();
        scheduleAutoRefresh();
        if (DesktopPreferences.getAutoRefreshStartup()) {
            refreshAll();
        }
    }

    private void applyProxy() {
        String host = DesktopPreferences.getProxyHost();
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
        if (autoRefreshTask != null) {
            autoRefreshTask.cancel(false);
            autoRefreshTask = null;
        }
        int minutes = DesktopPreferences.getAutoRefreshMinutes();
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
                Platform.runLater(feedList::refresh);
                Feed selected = feedList.getSelectionModel().getSelectedItem();
                if (selected != null) {
                    loadEpisodes(selected);
                }
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
        subscribeButton.setOnAction(event -> subscribe(urlField.getText().trim()));
        TextField searchField = new TextField();
        searchField.setPromptText("Search podcasts…");
        searchField.setPrefWidth(220);
        Button searchButton = new Button("Search", Icons.search());
        searchButton.setOnAction(event -> search(searchField.getText().trim()));
        searchField.setOnAction(event -> search(searchField.getText().trim()));
        Button refreshAllButton = new Button("Refresh all", Icons.refresh());
        refreshAllButton.setOnAction(event -> refreshAll());
        Button queueButton = new Button("Queue", Icons.queue());
        queueButton.setOnAction(event -> showQueue());
        Button importButton = new Button("Import", Icons.download());
        importButton.setOnAction(event -> importOpml());
        Button exportButton = new Button("Export", Icons.upload());
        exportButton.setOnAction(event -> exportOpml());
        Button syncButton = new Button("Sync", Icons.sync());
        this.syncButton = syncButton;
        updateSyncButtonTooltip();
        syncButton.setOnAction(event -> showSyncDialog());
        Button favoritesButton = new Button("Favorites", Icons.favorite());
        favoritesButton.setOnAction(event -> showFavorites());
        Button historyButton = new Button("History", Icons.history());
        historyButton.setOnAction(event -> showHistory());
        Button statsButton = new Button("Stats", Icons.stats());
        statsButton.setOnAction(event -> showStatistics());
        Button settingsButton = new Button("Settings", Icons.settings());
        settingsButton.setOnAction(event -> showSettings());
        return new ToolBar(urlField, subscribeButton, searchField, searchButton, refreshAllButton,
                queueButton, importButton, exportButton, syncButton, favoritesButton,
                historyButton, statsButton, settingsButton);
    }

    private VBox buildFeedPane() {
        feedList = new ListView<>(feeds);
        feedList.getSelectionModel().setSelectionMode(SelectionMode.SINGLE);
        feedList.setPrefWidth(280);
        feedList.setCellFactory(list -> new FeedCell());
        feedList.getSelectionModel().selectedItemProperty().addListener((obs, oldFeed, newFeed) -> {
            if (newFeed != null) {
                loadEpisodes(newFeed);
            }
        });
        Button refreshButton = new Button("Refresh");
        refreshButton.setOnAction(event -> {
            Feed selected = feedList.getSelectionModel().getSelectedItem();
            if (selected != null) {
                refreshFeed(selected);
            }
        });
        Button unsubscribeButton = new Button("Unsubscribe");
        unsubscribeButton.setOnAction(event -> {
            Feed selected = feedList.getSelectionModel().getSelectedItem();
            if (selected != null) {
                unsubscribe(selected);
            }
        });
        Button settingsButton = new Button("Feed settings");
        settingsButton.setOnAction(event -> {
            Feed selected = feedList.getSelectionModel().getSelectedItem();
            if (selected != null) {
                showFeedSettings(selected);
            }
        });
        HBox buttons = new HBox(8, refreshButton, unsubscribeButton, settingsButton);
        buttons.setPadding(new Insets(8));
        VBox pane = new VBox(4, new Label("Subscriptions"), feedList, buttons);
        pane.setPadding(new Insets(8));
        VBox.setVgrow(feedList, Priority.ALWAYS);
        return pane;
    }

    private class FeedCell extends ListCell<Feed> {
        private final ImageView art = new ImageView();
        private final Label titleLabel = new Label();
        private final Label countLabel = new Label();
        private final HBox row;

        FeedCell() {
            art.setFitWidth(40);
            art.setFitHeight(40);
            titleLabel.setWrapText(true);
            countLabel.setStyle("-fx-text-fill: gray;");
            VBox texts = new VBox(2, titleLabel, countLabel);
            HBox.setHgrow(texts, Priority.ALWAYS);
            row = new HBox(8, art, texts);
        }

        @Override
        protected void updateItem(Feed feed, boolean empty) {
            super.updateItem(feed, empty);
            if (empty || feed == null) {
                setGraphic(null);
                return;
            }
            titleLabel.setText(feed.getTitle() != null ? feed.getTitle() : feed.getDownloadUrl());
            String unplayedText = "";
            try {
                int unplayed = database.countUnplayed(feed.getId());
                if (unplayed > 0) {
                    unplayedText = unplayed + " unplayed";
                }
            } catch (Exception e) {
                // ignore count on error
            }
            countLabel.setText(unplayedText);
            updateArt(feed.getImageUrl());
            setGraphic(row);
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
        HBox header = new HBox(8, feedTitleLabel, sortBox, playAllButton);
        HBox.setHgrow(feedTitleLabel, Priority.ALWAYS);
        episodeList = new ListView<>(episodes);
        episodeList.setCellFactory(list -> new EpisodeCell());
        episodeList.setOnMouseClicked(event -> {
            if (event.getClickCount() == 2) {
                FeedItem selected = episodeList.getSelectionModel().getSelectedItem();
                if (selected != null) {
                    showEpisodeDetails(selected);
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
        for (FeedItem item : new ArrayList<>(episodes)) {
            if (item.getMedia() != null) {
                playback.play(item, new ArrayList<>(episodes));
                setStatus("Playing from \"" + item.getTitle() + "\" to the end");
                return;
            }
        }
        setStatus("Nothing playable in this list");
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
        button.setTooltip(new Tooltip(tooltip));
        return button;
    }

    private VBox buildPlayerBar() {
        Button prevButton = iconButton(Icons.previous(), "Previous episode");
        prevButton.setOnAction(event -> playback.playPrevious());
        skipBackButton = iconButton(Icons.replay10(), "");
        skipBackButton.setOnAction(event ->
                playback.skip(-DesktopPreferences.getSkipBackSec() * 1000));
        playPauseButton = iconButton(Icons.play(26), "Play / pause");
        playPauseButton.setOnAction(event -> handlePlayPauseAction());
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
        nowPlayingArt.setFitWidth(36);
        nowPlayingArt.setFitHeight(36);
        nowPlayingArt.setVisible(false);
        nowPlayingArt.setManaged(false);

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
            }
        });

        speedBox = new ComboBox<>();
        speedBox.getItems().addAll(SPEED_OPTIONS);
        speedBox.setValue(closestSpeed(DesktopPreferences.getPlaybackSpeed()));
        speedBox.setOnAction(event -> {
            String value = speedBox.getValue().replace("x", "");
            playback.setRate(Float.parseFloat(value));
        });

        volumeSlider = new Slider(0, 1, DesktopPreferences.getDefaultVolume());
        volumeSlider.setPrefWidth(100);
        lastVolume = DesktopPreferences.getDefaultVolume() > 0
                ? DesktopPreferences.getDefaultVolume() : 1.0;
        volumeSlider.valueProperty().addListener((obs, oldValue, newValue) -> {
            double volume = newValue.doubleValue();
            playback.setVolume(volume);
            DesktopPreferences.setDefaultVolume(volume);
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
        chapterLabel.setStyle("-fx-text-fill: gray;");
        chapterLabel.setPrefWidth(160);
        chapterLabel.setMaxWidth(160);

        chapterPrevButton = iconButton(Icons.navigateBefore(), "Previous chapter");
        chapterPrevButton.setOnAction(event -> skipChapter(false));
        chapterNextButton = iconButton(Icons.navigateAfter(), "Next chapter");
        chapterNextButton.setOnAction(event -> skipChapter(true));
        setChapterButtonsVisible(false);

        HBox scrubRow = new HBox(8, elapsedLabel, seekSlider, totalLabel, chapterLabel,
                chapterPrevButton, chapterNextButton);
        scrubRow.setAlignment(Pos.CENTER_LEFT);
        scrubRow.setPadding(new Insets(8, 8, 0, 8));
        HBox.setHgrow(seekSlider, Priority.ALWAYS);
        scrubRow.setOnScroll(event -> {
            if (Math.abs(event.getDeltaY()) >= 20 && playback.getCurrentMedia() != null) {
                playback.skip(event.getDeltaY() > 0 ? 10000 : -10000);
                event.consume();
            }
        });

        HBox playerRow = new HBox(8, prevButton, skipBackButton, playPauseHolder, skipForwardButton,
                nextButton, stopButton, nowPlayingArt, nowPlayingLabel, speedBox, muteButton,
                volumeSlider, sleepButton);
        playerRow.setAlignment(Pos.CENTER_LEFT);
        playerRow.setPadding(new Insets(8));
        statusLabel = new Label("Ready");
        statusLabel.setPadding(new Insets(0, 8, 8, 8));
        return new VBox(scrubRow, playerRow, statusLabel);
    }

    private static Label buildTimeLabel(Pos alignment) {
        Label label = new Label("0:00");
        label.setMinWidth(52);
        label.setAlignment(alignment);
        label.setStyle("-fx-cursor: hand;");
        return label;
    }

    private void handlePlayPauseAction() {
        if (playback.getCurrentMedia() == null) {
            resumeLastPlayed();
        } else {
            playback.togglePlayPause();
        }
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
        Node focusOwner = scene != null ? scene.getFocusOwner() : null;
        if (focusOwner instanceof TextInputControl || focusOwner instanceof WebView) {
            return;
        }
        switch (event.getCode()) {
            case SPACE:
                handlePlayPauseAction();
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
            default:
                break;
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

    private void updateLoadingIndicator() {
        if (loadingSpinner != null) {
            loadingSpinner.setVisible(loadingMediaId != -1);
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

    private void setStatus(String message) {
        Platform.runLater(() -> statusLabel.setText(message));
    }

    private void reloadFeeds(Long selectFeedId) {
        background.submit(() -> {
            try {
                List<Feed> all = database.getAllFeeds();
                Map<Long, Long> lastPlayed = database.getFeedLastPlayedTimes();
                Platform.runLater(() -> {
                    feedLastPlayed.clear();
                    feedLastPlayed.putAll(lastPlayed);
                    FeedSorter.sortByLastPlayed(all, lastPlayed);
                    feeds.setAll(all);
                    feedList.refresh();
                    if (selectFeedId != null) {
                        for (Feed feed : all) {
                            if (feed.getId() == selectFeedId) {
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
        selectedFeed = feed;
        background.submit(() -> {
            try {
                Feed full = database.getFeed(feed.getId());
                FeedPrefs prefs = database.getFeedPrefs(feed.getId());
                List<FeedItem> items = full.getItems() != null
                        ? new ArrayList<>(full.getItems()) : new ArrayList<>();
                EpisodeSorter.sort(items, prefs.sortCode);
                Platform.runLater(() -> {
                    feedTitleLabel.setText(full.getTitle() != null ? full.getTitle() : full.getDownloadUrl());
                    sortBoxProgrammatic = true;
                    try {
                        sortBox.setValue(sortLabel(prefs.sortCode));
                    } finally {
                        sortBoxProgrammatic = false;
                    }
                    episodes.setAll(items);
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
        background.submit(() -> {
            try {
                Feed feed = feedUpdater.subscribe(url);
                setStatus("Subscribed to " + feed.getTitle());
                reloadFeeds(feed.getId());
            } catch (Exception e) {
                setStatus("Subscribe failed: " + e.getMessage());
            }
        });
    }

    private void refreshFeed(Feed feed) {
        setStatus("Refreshing " + feed.getTitle() + "…");
        background.submit(() -> {
            try {
                List<FeedItem> added = feedUpdater.refresh(feed);
                autoDownloadNew(feed, added);
                setStatus("Refreshed " + feed.getTitle() + ": " + added.size() + " new episodes");
                loadEpisodes(feed);
                Platform.runLater(feedList::refresh);
            } catch (Exception e) {
                setStatus("Refresh failed: " + e.getMessage());
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
        background.submit(() -> {
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
                Feed selected = feedList.getSelectionModel().getSelectedItem();
                if (selected != null) {
                    loadEpisodes(selected);
                }
                Platform.runLater(feedList::refresh);
            } catch (Exception e) {
                setStatus("Refresh failed: " + e.getMessage());
            }
        });
    }

    private void unsubscribe(Feed feed) {
        background.submit(() -> {
            try {
                for (FeedItem item : database.getItemsOfFeed(feed.getId())) {
                    if (item.getMedia() != null && item.getMedia().getLocalFileUrl() != null) {
                        new File(item.getMedia().getLocalFileUrl()).delete();
                    }
                }
                database.deleteFeed(feed.getId());
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
        background.submit(() -> {
            try {
                boolean favorite = !item.isTagged(FeedItem.TAG_FAVORITE);
                database.setFavorite(item.getId(), favorite);
                if (favorite) {
                    item.addTag(FeedItem.TAG_FAVORITE);
                } else {
                    item.removeTag(FeedItem.TAG_FAVORITE);
                }
                Platform.runLater(episodeList::refresh);
            } catch (Exception e) {
                setStatus("Could not update favorite: " + e.getMessage());
            }
        });
    }

    private void showFavorites() {
        background.submit(() -> {
            try {
                List<FeedItem> favorites = database.getFavorites();
                Platform.runLater(() -> {
                    Stage dialog = new Stage();
                    dialog.initModality(Modality.APPLICATION_MODAL);
                    dialog.setTitle("Favorites");
                    ObservableList<FeedItem> items = FXCollections.observableArrayList(favorites);
                    ListView<FeedItem> list = new ListView<>(items);
                    list.setCellFactory(view -> new ListCell<>() {
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
                    });
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
                    dialog.setScene(new Scene(pane, 560, 420));
                    dialog.show();
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
        Stage dialog = new Stage();
        dialog.initModality(Modality.APPLICATION_MODAL);
        dialog.setTitle("Feed settings: " + feed.getTitle());
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
        Button saveButton = new Button("Save");
        saveButton.setOnAction(event -> background.submit(() -> {
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
                    dialog.close();
                    loadEpisodes(feed);
                });
            } catch (Exception e) {
                setStatus("Could not save feed settings: " + e.getMessage());
            }
        }));
        grid.add(saveButton, 0, row, 2, 1);
        dialog.setScene(new Scene(new VBox(grid), 480, 420));
        dialog.show();
    }

    private static String speedLabel(float speed) {
        return speed <= 0 ? "global" : String.format(Locale.US, "%.2fx", speed);
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
                    Stage dialog = new Stage();
                    dialog.initModality(Modality.APPLICATION_MODAL);
                    dialog.setTitle("Playback history");
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
                    dialog.setScene(new Scene(pane, 560, 420));
                    dialog.show();
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
                    Stage dialog = new Stage();
                    dialog.initModality(Modality.APPLICATION_MODAL);
                    dialog.setTitle("Statistics");
                    ListView<String> list = new ListView<>(FXCollections.observableArrayList(lines));
                    VBox pane = new VBox(8, list);
                    pane.setPadding(new Insets(8));
                    dialog.setScene(new Scene(pane, 560, 420));
                    dialog.show();
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
        Stage dialog = new Stage();
        dialog.initModality(Modality.APPLICATION_MODAL);
        dialog.setTitle("Settings");
        javafx.scene.layout.GridPane grid = new javafx.scene.layout.GridPane();
        grid.setHgap(8);
        grid.setVgap(8);
        grid.setPadding(new Insets(12));
        int row = 0;
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
        javafx.scene.control.CheckBox skipSilenceBox =
                new javafx.scene.control.CheckBox("Skip silence (fast-forward quiet parts)");
        skipSilenceBox.setSelected(DesktopPreferences.getSkipSilence());
        grid.add(skipSilenceBox, 0, row++, 2, 1);
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
        Button saveButton = new Button("Save");
        Label savedLabel = new Label("");
        saveButton.setOnAction(event -> {
            try {
                DesktopPreferences.setPlaybackSpeed(
                        Float.parseFloat(settingsSpeedBox.getValue().replace("x", "")));
                DesktopPreferences.setSkipIntroSec(parseNonNegative(introField.getText()));
                DesktopPreferences.setSkipEndingSec(parseNonNegative(endingField.getText()));
                DesktopPreferences.setSkipBackSec(parseNonNegative(skipBackField.getText()));
                DesktopPreferences.setSkipForwardSec(parseNonNegative(skipForwardField.getText()));
                updateSkipTooltips();
                DesktopPreferences.setVolumeBoostDb((int) boostSlider.getValue());
                DesktopPreferences.setSkipSilence(skipSilenceBox.isSelected());
                DesktopPreferences.setAutoDownloadDefault(downloadBox.isSelected());
                DesktopPreferences.setAutoDeleteDefault(deleteBox.isSelected());
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
                applyProxy();
                scheduleAutoRefresh();
                savedLabel.setText(
                        "Saved — speed/skip/silence/boost apply to newly started playback.");
                setStatus("Settings saved");
            } catch (Exception e) {
                savedLabel.setText("Could not save: " + e.getMessage());
            }
        });
        grid.add(saveButton, 0, row);
        grid.add(savedLabel, 1, row++);
        javafx.scene.control.ScrollPane scroll = new javafx.scene.control.ScrollPane(grid);
        scroll.setFitToWidth(true);
        scroll.setPrefSize(520, 560);
        dialog.setScene(new Scene(new VBox(scroll), 540, 580));
        dialog.show();
    }

    private static Label sectionLabel(String text) {
        Label label = new Label(text);
        label.setStyle("-fx-font-weight: bold; -fx-font-size: 14px;");
        return label;
    }

    private static int parseNonNegative(String text) {
        try {
            return Math.max(Integer.parseInt(text.trim()), 0);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private void showEpisodeDetails(FeedItem item) {
        Stage dialog = new Stage();
        dialog.initModality(Modality.APPLICATION_MODAL);
        dialog.setTitle(item.getTitle() != null ? item.getTitle() : "Episode");
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
        javafx.scene.web.WebView webView = new javafx.scene.web.WebView();
        javafx.scene.web.WebEngine engine = webView.getEngine();
        String page = Shownotes.toPage(item.getTitle(), item.getDescription());
        engine.loadContent(page);
        engine.locationProperty().addListener((obs, oldLocation, newLocation) -> {
            if (newLocation != null
                    && (newLocation.startsWith("http://") || newLocation.startsWith("https://"))) {
                getHostServices().showDocument(newLocation);
                Platform.runLater(() -> engine.loadContent(page));
            }
        });
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
                        dialog.close();
                    }
                }
            });
            pane.getChildren().add(pane.getChildren().size() - 1, chaptersLabel);
            pane.getChildren().add(pane.getChildren().size() - 1, chapterList);
        }
        VBox.setVgrow(webView, Priority.ALWAYS);
        dialog.setScene(new Scene(pane, 700, 560));
        dialog.show();
    }

    private void showTranscript(FeedItem item) {
        setStatus("Loading transcript…");
        background.submit(() -> {
            try {
                de.danoeh.antennapod.model.feed.Transcript transcript = TranscriptFetcher.fetch(item);
                Platform.runLater(() -> {
                    Stage dialog = new Stage();
                    dialog.initModality(Modality.APPLICATION_MODAL);
                    dialog.setTitle("Transcript: " + item.getTitle());
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
                                dialog.close();
                            }
                        }
                    });
                    VBox pane = new VBox(8, list);
                    pane.setPadding(new Insets(8));
                    dialog.setScene(new Scene(pane, 640, 480));
                    dialog.show();
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
            playback.playAt(item, new ArrayList<>(episodes), positionMs);
        }
    }

    private void showSyncDialog() {
        Stage dialog = new Stage();
        dialog.initModality(Modality.APPLICATION_MODAL);
        dialog.setTitle("Sync settings");
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
        Label syncStatus = new Label(syncStatusText());
        syncStatus.setWrapText(true);
        javafx.scene.control.CheckBox autoSyncBox = new javafx.scene.control.CheckBox(
                "Sync automatically (at startup and after playback)");
        autoSyncBox.setSelected(DesktopPreferences.getAutoSyncPlayback());
        Button saveButton = new Button("Save");
        saveButton.setOnAction(event -> {
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
        });
        Button testButton = new Button("Test login");
        testButton.setOnAction(event -> {
            saveButton.fire();
            syncStatus.setText("Testing login…");
            background.submit(() -> {
                try {
                    syncManager.testLogin();
                    Platform.runLater(() -> syncStatus.setText("Login successful"));
                } catch (Exception e) {
                    Platform.runLater(() -> syncStatus.setText("Login failed: " + e.getMessage()));
                }
            });
        });
        Button syncNowButton = new Button("Sync now");
        syncNowButton.setOnAction(event -> {
            saveButton.fire();
            runSync(
                    () -> {
                        syncNowButton.setDisable(true);
                        syncStatus.setText("Syncing…");
                        setStatus("Syncing…");
                    },
                    message -> {
                        syncStatus.setText(message);
                        syncNowButton.setDisable(false);
                    });
        });
        Button devicesButton = new Button("Import from another device…");
        devicesButton.setOnAction(event -> {
            saveButton.fire();
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
        grid.add(new HBox(8, saveButton, testButton, syncNowButton), 0, 5, 2, 1);
        grid.add(devicesButton, 0, 6, 2, 1);
        grid.add(autoSyncBox, 0, 7, 2, 1);
        grid.add(syncStatus, 0, 8, 2, 1);
        dialog.setScene(new Scene(new VBox(grid), 460, 430));
        dialog.show();
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
        Stage dialog = new Stage();
        dialog.initModality(Modality.APPLICATION_MODAL);
        dialog.setTitle("Devices on sync account");
        Label status = new Label("Loading devices…");
        status.setWrapText(true);
        ListView<de.danoeh.antennapod.net.sync.gpoddernet.model.GpodnetDevice> list = new ListView<>();
        list.setCellFactory(view -> new ListCell<>() {
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
                importButton.setOnAction(event -> importDeviceSubscriptions(device, status, dialog));
                HBox row = new HBox(8, title, importButton);
                HBox.setHgrow(title, Priority.ALWAYS);
                setGraphic(row);
                setText(null);
            }
        });
        VBox pane = new VBox(8, status, list);
        pane.setPadding(new Insets(8));
        VBox.setVgrow(list, Priority.ALWAYS);
        dialog.setScene(new Scene(pane, 560, 380));
        dialog.show();
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
            Label status, Stage dialog) {
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
                String message = syncResultText(result);
                String summary = result.playedItemIds.isEmpty()
                        ? "Sync finished"
                        : "Sync finished: " + result.playedItemIds.size() + " marked finished";
                Platform.runLater(() -> {
                    syncedItemIds.clear();
                    syncedItemIds.addAll(result.changedItemIds);
                    if (onFinish != null) {
                        onFinish.accept(message);
                    }
                    updateSyncButtonTooltip();
                    reloadFeeds(null);
                    episodeList.refresh();
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
        background.submit(() -> {
            try {
                List<PodcastSearchResult> results =
                        new CombinedSearcher().search(query).blockingGet();
                Platform.runLater(() -> showSearchResults(query, results));
                setStatus("Found " + results.size() + " results for \"" + query + "\"");
            } catch (Exception e) {
                setStatus("Search failed: " + e.getMessage());
            }
        });
    }

    private void showSearchResults(String query, List<PodcastSearchResult> results) {
        Stage dialog = new Stage();
        dialog.initModality(Modality.APPLICATION_MODAL);
        dialog.setTitle("Search results: " + query);
        ObservableList<PodcastSearchResult> items = FXCollections.observableArrayList(results);
        ListView<PodcastSearchResult> list = new ListView<>(items);
        list.setCellFactory(view -> new ListCell<>() {
            @Override
            protected void updateItem(PodcastSearchResult result, boolean empty) {
                super.updateItem(result, empty);
                if (empty || result == null) {
                    setText(null);
                    setGraphic(null);
                    return;
                }
                Label title = new Label(result.title
                        + (result.author != null && !result.author.isEmpty() ? " — " + result.author : ""));
                title.setWrapText(true);
                Button subscribeButton = new Button("Subscribe");
                subscribeButton.setOnAction(event -> {
                    if (result.feedUrl != null) {
                        dialog.close();
                        subscribe(result.feedUrl);
                    }
                });
                HBox row = new HBox(8, title, subscribeButton);
                HBox.setHgrow(title, Priority.ALWAYS);
                setGraphic(row);
                setText(null);
            }
        });
        VBox pane = new VBox(8, list);
        pane.setPadding(new Insets(8));
        dialog.setScene(new Scene(pane, 560, 420));
        dialog.show();
    }

    private void togglePlayed(FeedItem item) {
        background.submit(() -> {
            try {
                boolean played = !item.isPlayed();
                item.setPlayed(played);
                database.setItemState(item.getId(), item.getPlayState());
                syncManager.recordPlayedState(item, played);
                Platform.runLater(episodeList::refresh);
            } catch (Exception e) {
                setStatus("Could not update episode: " + e.getMessage());
            }
        });
    }

    private void enqueue(FeedItem item) {
        background.submit(() -> {
            try {
                database.addToQueue(item.getId());
                setStatus("Added to queue: " + item.getTitle());
            } catch (Exception e) {
                setStatus("Could not add to queue: " + e.getMessage());
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
        Stage dialog = new Stage();
        dialog.initModality(Modality.APPLICATION_MODAL);
        dialog.setTitle("Queue");
        ObservableList<FeedItem> queueItems = FXCollections.observableArrayList(initialQueue);
        ListView<FeedItem> queueList = new ListView<>(queueItems);
        queueList.setCellFactory(view -> new ListCell<>() {
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
        });
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
        dialog.setScene(new Scene(pane, 560, 420));
        dialog.show();
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
        if (downloader.isDownloading(media.getId())) {
            downloader.cancel(media.getId());
            return;
        }
        if (media.localFileAvailable() && media.getLocalFileUrl() != null) {
            background.submit(() -> {
                new File(media.getLocalFileUrl()).delete();
                media.setLocalFileUrl(null);
                try {
                    database.updateMedia(media);
                } catch (Exception e) {
                    setStatus("Could not update episode: " + e.getMessage());
                }
                Platform.runLater(episodeList::refresh);
            });
            return;
        }
        downloadProgress.put(media.getId(), 0);
        downloader.enqueue(media, this);
        episodeList.refresh();
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
                    database.updateMedia(media);
                    setStatus("Auto-deleted: " + media.getHumanReadableIdentifier());
                    Platform.runLater(episodeList::refresh);
                }
            } catch (Exception e) {
                setStatus("Auto-delete failed: " + e.getMessage());
            }
        });
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
        Platform.runLater(episodeList::refresh);
        Feed selected = feedList.getSelectionModel().getSelectedItem();
        if (selected != null) {
            loadEpisodes(selected);
        }
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
        String artUrl = null;
        if (current != null && current.getItem() != null) {
            artUrl = current.getItem().getImageUrl();
            if (artUrl == null || artUrl.isEmpty()) {
                artUrl = feedImageUrl(current.getItem().getFeedId());
            }
        }
        if (artUrl == null || artUrl.isEmpty()) {
            nowPlayingArt.setUserData(null);
            nowPlayingArt.setImage(null);
            nowPlayingArt.setVisible(false);
            nowPlayingArt.setManaged(false);
            return;
        }
        if (!artUrl.equals(nowPlayingArt.getUserData())) {
            nowPlayingArt.setUserData(artUrl);
            nowPlayingArt.setImage(ImageCache.get(artUrl, 36, 36));
        }
        nowPlayingArt.setVisible(true);
        nowPlayingArt.setManaged(true);
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
            playPauseButton.setGraphic(playback != null && playback.isPlaying()
                    ? Icons.pause(26) : Icons.play(26));
        }
    }

    @Override
    public void onStateChanged() {
        updatePlayPauseButton();
        updateTransportEnabled();
        FeedMedia current = playback.getCurrentMedia();
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
        updatePlayPauseButton();
        if (trayActive) {
            trayManager.update(playback.isPlaying(), title);
        }
        episodeList.refresh();
    }

    @Override
    public void onLoadingChanged(boolean loading) {
        FeedMedia current = playback.getCurrentMedia();
        loadingMediaId = loading && current != null ? current.getId() : -1;
        episodeList.refresh();
        updateLoadingIndicator();
    }

    @Override
    public void onPositionChanged(int positionMs, int durationMs) {
        if (sliderDragging) {
            return;
        }
        seekSlider.setMax(Math.max(durationMs, 1));
        seekSlider.setValue(Math.min(positionMs, Math.max(durationMs, 1)));
        updateTimeLabels(positionMs, durationMs);
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

    private void shutdown() {
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
        downloader.shutdown();
        background.shutdownNow();
        try {
            database.close();
        } catch (Exception e) {
            // ignore
        }
        Platform.exit();
    }

    private class EpisodeCell extends ListCell<FeedItem> {
        private final Label titleLabel = new Label();
        private final Label metaLabel = new Label();
        private final Label syncBadge = new Label("SYNCED");
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
            titleLabel.setWrapText(false);
            titleLabel.setStyle("-fx-font-weight: bold;");
            titleLabel.setMaxWidth(Double.MAX_VALUE);
            titleLabel.setTooltip(titleTooltip);
            metaLabel.setWrapText(false);
            metaLabel.setStyle("-fx-text-fill: gray;");
            metaLabel.setMaxWidth(Double.MAX_VALUE);
            metaLabel.setTooltip(metaTooltip);
            playButton.setTooltip(playTooltip);
            syncBadge.setStyle("-fx-background-color: -fx-accent; -fx-text-fill: white; "
                    + "-fx-background-radius: 8; -fx-padding: 1 6 1 6; -fx-font-size: 10px;");
            syncBadge.setTooltip(new Tooltip("Updated by the last sync"));
            syncBadge.setVisible(false);
            syncBadge.setManaged(false);
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
                        playback.play(item, new ArrayList<>(episodes));
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
        }

        @Override
        protected void updateItem(FeedItem item, boolean empty) {
            super.updateItem(item, empty);
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
            if (item.isNew()) {
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
            } else if (media != null && downloader.isDownloading(media.getId())) {
                Integer percent = downloadProgress.get(media.getId());
                meta.append(" · Downloading")
                        .append(percent != null && percent >= 0 ? " " + percent + "%" : "…");
                downloadButton.setText("Cancel");
            } else {
                downloadButton.setText("Download");
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
                titleLabel.setStyle("-fx-font-weight: normal; -fx-text-fill: gray;");
            } else {
                titleLabel.setStyle("-fx-font-weight: bold;");
            }
            VBox texts = new VBox(2, titleLabel, metaLabel);
            HBox.setHgrow(texts, Priority.ALWAYS);
            favoriteButton.setGraphic(
                    Icons.star(item.isTagged(FeedItem.TAG_FAVORITE)));
            HBox row = new HBox(8, art, texts, syncBadge, playButton, downloadButton, queueButton,
                    favoriteButton, infoButton, playedButton);
            row.setPadding(new Insets(4));
            if (isCurrent) {
                row.setStyle("-fx-background-color: derive(-fx-accent, 85%); -fx-background-radius: 4;");
            } else {
                row.setStyle("");
            }
            setGraphic(row);
            setText(null);
        }
    }
}
