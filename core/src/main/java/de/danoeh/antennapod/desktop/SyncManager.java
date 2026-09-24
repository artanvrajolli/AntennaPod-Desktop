package de.danoeh.antennapod.desktop;

import androidx.core.util.Pair;
import de.danoeh.antennapod.model.feed.Feed;
import de.danoeh.antennapod.model.feed.FeedItem;
import de.danoeh.antennapod.model.feed.FeedMedia;
import de.danoeh.antennapod.net.common.AntennapodHttpClient;
import de.danoeh.antennapod.net.sync.gpoddernet.GpodnetService;
import de.danoeh.antennapod.net.sync.gpoddernet.model.GpodnetDevice;
import de.danoeh.antennapod.net.sync.nextcloud.NextcloudSyncService;
import de.danoeh.antennapod.net.sync.service.EpisodeActionFilter;
import de.danoeh.antennapod.net.sync.serviceinterface.EpisodeAction;
import de.danoeh.antennapod.net.sync.serviceinterface.EpisodeActionChanges;
import de.danoeh.antennapod.net.sync.serviceinterface.ISyncService;
import de.danoeh.antennapod.net.sync.serviceinterface.SubscriptionChanges;
import de.danoeh.antennapod.net.sync.serviceinterface.UploadChangesResponse;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class SyncManager {
    private static final String STATE_SUB_TIMESTAMP = "syncSubTimestamp";
    private static final String STATE_PENDING_SUBSCRIPTIONS = "syncPendingSubscriptions";
    /** How close to the end a synced position has to be for the episode to count as played. */
    private static final int ALMOST_ENDED_MS = 30_000;
    private static final String STATE_ACTION_TIMESTAMP = "syncActionTimestamp";

    private final DesktopDatabase database;
    private final FeedUpdater feedUpdater;

    public SyncManager(DesktopDatabase database, FeedUpdater feedUpdater) {
        this.database = database;
        this.feedUpdater = feedUpdater;
        DesktopHttp.init();
    }

    protected ISyncService createService() {
        String provider = DesktopPreferences.getSyncProvider();
        if ("nextcloud".equals(provider)) {
            return new NextcloudSyncService(AntennapodHttpClient.getHttpClient(),
                    DesktopPreferences.getSyncHost(), DesktopPreferences.getSyncUsername(),
                    DesktopPreferences.getSyncPassword());
        }
        return new GpodnetService(AntennapodHttpClient.getHttpClient(),
                DesktopPreferences.getSyncHost(), DesktopPreferences.getSyncDeviceId(),
                DesktopPreferences.getSyncUsername(), DesktopPreferences.getSyncPassword());
    }

    public void testLogin() throws Exception {
        ISyncService service = createService();
        service.login();
        service.logout();
    }

    public List<GpodnetDevice> listDevices() throws Exception {
        ISyncService service = createService();
        if (!(service instanceof GpodnetService)) {
            return new ArrayList<>();
        }
        service.login();
        try {
            return ((GpodnetService) service).getDevices();
        } finally {
            service.logout();
        }
    }

    public int importFromDevice(String deviceId) throws Exception {
        GpodnetService service = createServiceForDevice(deviceId);
        service.login();
        try {
            SubscriptionChanges changes = service.getSubscriptionChanges(0);
            Set<String> localUrls = new HashSet<>();
            for (Feed feed : database.getAllFeeds()) {
                localUrls.add(feed.getDownloadUrl());
            }
            int added = 0;
            for (String url : changes.getAdded()) {
                if (!localUrls.contains(url)) {
                    try {
                        feedUpdater.subscribe(url);
                        localUrls.add(url);
                        added++;
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            }
            return added;
        } finally {
            service.logout();
        }
    }

    protected GpodnetService createServiceForDevice(String deviceId) {
        return new GpodnetService(AntennapodHttpClient.getHttpClient(),
                DesktopPreferences.getSyncHost(), deviceId,
                DesktopPreferences.getSyncUsername(), DesktopPreferences.getSyncPassword());
    }

    public SyncResult sync() throws Exception {
        ISyncService service = createService();
        service.login();
        try {
            ensureDevice(service);
            int subsAdded = syncSubscriptions(service);
            List<DesktopDatabase.SyncAction> queued = database.getQueuedSyncActions();
            List<EpisodeAction> localActions = toEpisodeActions(queued);
            int actionsUploaded = uploadActions(service, queued);
            List<Long> changedIds = new ArrayList<>();
            List<Long> playedIds = new ArrayList<>();
            List<Long> unplayedIds = new ArrayList<>();
            List<Long> syncedItemIds = new ArrayList<>();
            int actionsApplied = downloadAndApplyActions(service, localActions, changedIds,
                    playedIds, unplayedIds, syncedItemIds);
            for (long itemId : syncedItemIds) {
                database.setSyncedPosition(itemId, database.getItem(itemId).getMedia().getPosition());
            }
            SyncResult result = new SyncResult(subsAdded, actionsUploaded, actionsApplied, changedIds,
                    playedIds, unplayedIds);
            result.syncedItemIds.addAll(syncedItemIds);
            return result;
        } finally {
            service.logout();
        }
    }

    /** Subscriptions added on another device that could not be subscribed to yet. */
    List<String> pendingSubscriptions() throws Exception {
        String stored = database.getSyncState(STATE_PENDING_SUBSCRIPTIONS, "");
        List<String> urls = new ArrayList<>();
        for (String url : stored.split("\n")) {
            if (!url.isBlank()) {
                urls.add(url.trim());
            }
        }
        return urls;
    }

    private void ensureDevice(ISyncService service) {
        if (!(service instanceof GpodnetService)) {
            return;
        }
        GpodnetService gpodnet = (GpodnetService) service;
        try {
            boolean found = false;
            for (GpodnetDevice device : gpodnet.getDevices()) {
                if (DesktopPreferences.getSyncDeviceId().equals(device.getId())) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                gpodnet.configureDevice(DesktopPreferences.getSyncDeviceId(),
                        DesktopPreferences.getSyncDeviceCaption(), GpodnetDevice.DeviceType.DESKTOP);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private int syncSubscriptions(ISyncService service) throws Exception {
        long lastSync = Long.parseLong(database.getSyncState(STATE_SUB_TIMESTAMP, "0"));
        SubscriptionChanges changes = service.getSubscriptionChanges(lastSync);
        database.setSyncState(STATE_SUB_TIMESTAMP, String.valueOf(changes.getTimestamp()));

        Set<String> localUrls = new HashSet<>();
        for (Feed feed : database.getAllFeeds()) {
            localUrls.add(feed.getDownloadUrl());
        }
        // The timestamp above has already moved past these changes, so a subscription that fails
        // now (a timeout, a 5xx) would never be offered again. Keep it and retry on later syncs.
        Set<String> wanted = new LinkedHashSet<>(pendingSubscriptions());
        wanted.addAll(changes.getAdded());
        wanted.removeAll(changes.getRemoved());
        List<String> stillPending = new ArrayList<>();
        int added = 0;
        for (String url : wanted) {
            if (!localUrls.contains(url)) {
                try {
                    feedUpdater.subscribe(url);
                    added++;
                } catch (Exception e) {
                    e.printStackTrace();
                    stillPending.add(url);
                }
            }
        }
        database.setSyncState(STATE_PENDING_SUBSCRIPTIONS, String.join("\n", stillPending));
        for (String url : changes.getRemoved()) {
            Feed feed = database.getFeedByDownloadUrl(url);
            if (feed != null) {
                feedUpdater.unsubscribe(feed.getId());
            }
        }

        Set<String> currentLocal = new HashSet<>();
        for (Feed feed : database.getAllFeeds()) {
            currentLocal.add(feed.getDownloadUrl());
        }
        Set<String> snapshot = new HashSet<>(database.getSyncSubscriptionSnapshot());
        List<String> toAdd = new ArrayList<>();
        List<String> toRemove = new ArrayList<>();
        for (String url : currentLocal) {
            if (!snapshot.contains(url)) {
                toAdd.add(url);
            }
        }
        for (String url : snapshot) {
            if (!currentLocal.contains(url)) {
                toRemove.add(url);
            }
        }
        if (!toAdd.isEmpty() || !toRemove.isEmpty()) {
            UploadChangesResponse response = service.uploadSubscriptionChanges(toAdd, toRemove);
            if (response != null) {
                database.setSyncState(STATE_SUB_TIMESTAMP, String.valueOf(response.timestamp));
            }
        }
        database.setSyncSubscriptionSnapshot(new ArrayList<>(currentLocal));
        return added;
    }

    private List<EpisodeAction> toEpisodeActions(List<DesktopDatabase.SyncAction> queued) {
        List<EpisodeAction> actions = new ArrayList<>();
        for (DesktopDatabase.SyncAction action : queued) {
            EpisodeAction.Builder builder = new EpisodeAction.Builder(
                    action.podcast, action.episode, EpisodeAction.Action.valueOf(action.action));
            builder.timestamp(new Date(action.timestamp));
            if (action.guid != null) {
                builder.guid(action.guid);
            }
            if (action.started >= 0) {
                builder.started(action.started);
            }
            if (action.position >= 0) {
                builder.position(action.position);
            }
            if (action.total >= 0) {
                builder.total(action.total);
            }
            actions.add(builder.build());
        }
        return actions;
    }

    private int uploadActions(ISyncService service, List<DesktopDatabase.SyncAction> queued)
            throws Exception {
        if (queued.isEmpty()) {
            return 0;
        }
        List<EpisodeAction> actions = toEpisodeActions(queued);
        UploadChangesResponse response = service.uploadEpisodeActions(actions);
        List<Long> uploadedIds = new ArrayList<>();
        for (DesktopDatabase.SyncAction action : queued) {
            uploadedIds.add(action.id);
        }
        database.deleteSyncActions(uploadedIds);
        return actions.size();
    }

    private int downloadAndApplyActions(ISyncService service, List<EpisodeAction> localActions,
            List<Long> changedIds, List<Long> playedIds, List<Long> unplayedIds,
            List<Long> syncedItemIds) throws Exception {
        long lastSync = Long.parseLong(database.getSyncState(STATE_ACTION_TIMESTAMP, "0"));
        EpisodeActionChanges changes = service.getEpisodeActionChanges(lastSync);
        database.setSyncState(STATE_ACTION_TIMESTAMP, String.valueOf(changes.getTimestamp()));
        // An episode can finish on this device while the upload above is in flight. That finish
        // is queued after the snapshot this sync uploaded, so without re-reading it an older
        // remote position would overwrite the just-finished state. The local finish wins; the
        // queued action uploads on the next sync.
        List<EpisodeAction> effectiveLocal = new ArrayList<>(localActions);
        try {
            effectiveLocal.addAll(toEpisodeActions(database.getQueuedSyncActions()));
        } catch (Exception e) {
            e.printStackTrace();
        }
        Map<Pair<String, String>, EpisodeAction> overriding =
                EpisodeActionFilter.getRemoteActionsOverridingLocalActions(
                        changes.getEpisodeActions(), effectiveLocal);
        int applied = 0;
        for (EpisodeAction action : overriding.values()) {
            if (applyPlayAction(action, changedIds, playedIds, unplayedIds)) {
                applied++;
            }
        }
        for (EpisodeAction action : changes.getEpisodeActions()) {
            try {
                FeedItem item = database.findItemByEpisodeUrl(
                        action.getPodcast(), action.getEpisode(), action.getGuid());
                if (item != null && !syncedItemIds.contains(item.getId())) {
                    syncedItemIds.add(item.getId());
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        return applied;
    }

    private boolean applyPlayAction(EpisodeAction action, List<Long> changedIds,
            List<Long> playedIds, List<Long> unplayedIds) {
        try {
            FeedItem item = database.findItemByEpisodeUrl(
                    action.getPodcast(), action.getEpisode(), action.getGuid());
            if (item == null || item.getMedia() == null) {
                return false;
            }
            FeedMedia media = item.getMedia();
            // the duration known here wins, as in AntennaPod for Android: the sender's total can
            // be a guess, and older builds of this app sent 1 second for an unknown duration,
            // which then marked episodes played and stored as one second long
            int durationMs = media.getDuration();
            if (durationMs <= 0 && action.getTotal() > 0) {
                durationMs = action.getTotal() * 1000;
                media.setDuration(durationMs);
            }
            if (action.getPosition() >= 0) {
                media.setPosition(action.getPosition() * 1000);
            }
            if (action.getTotal() > 0 && isAlmostEnded(action.getPosition() * 1000, durationMs)) {
                item.setPlayed(true);
                database.setItemState(item.getId(), item.getPlayState());
                media.setPosition(0);
                playedIds.add(item.getId());
            } else if (action.getTotal() > 0 && action.getPosition() <= 0 && item.isPlayed()) {
                item.setPlayed(false);
                database.setItemState(item.getId(), item.getPlayState());
                media.setPosition(0);
                unplayedIds.add(item.getId());
            }
            database.updatePlaybackState(media);
            changedIds.add(item.getId());
            return true;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    /**
     * Whether a position counts as having finished the episode: within 30 seconds of the end, or
     * within the last tenth for short episodes, so a 20-second clip is not finished by starting it.
     */
    static boolean isAlmostEnded(int positionMs, int durationMs) {
        if (durationMs <= 0 || positionMs < 0) {
            return false;
        }
        return positionMs >= durationMs - Math.min(ALMOST_ENDED_MS, durationMs / 10);
    }

    /** Total in seconds for an episode action; 0 when unknown, which receivers ignore. */
    static int totalSeconds(FeedMedia media) {
        return Math.max(media.getDuration() / 1000, 0);
    }

    public void recordPlayAction(FeedMedia media) {
        if (!DesktopPreferences.isSyncEnabled() || media == null || media.getItem() == null) {
            return;
        }
        try {
            FeedItem item = media.getItem();
            Feed feed = item.getFeed();
            if (feed == null && item.getFeedId() != 0) {
                feed = database.getFeed(item.getFeedId());
                item.setFeed(feed);
            }
            if (feed == null || media.getDownloadUrl() == null) {
                return;
            }
            EpisodeAction.Builder builder = new EpisodeAction.Builder(item, EpisodeAction.Action.PLAY);
            builder.currentTimestamp();
            builder.started(0);
            builder.position(media.getPosition() / 1000);
            builder.total(totalSeconds(media));
            EpisodeAction action = builder.build();
            database.enqueueSyncAction(action.getPodcast(), action.getEpisode(), action.getGuid(),
                    action.getAction().name(), action.getTimestamp().getTime(),
                    action.getStarted(), action.getPosition(), action.getTotal());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void recordPlayedState(FeedItem item, boolean played) {
        if (!DesktopPreferences.isSyncEnabled() || item == null || item.getMedia() == null) {
            return;
        }
        try {
            Feed feed = item.getFeed();
            if (feed == null && item.getFeedId() != 0) {
                feed = database.getFeed(item.getFeedId());
                item.setFeed(feed);
            }
            if (feed == null || item.getMedia().getDownloadUrl() == null) {
                return;
            }
            int totalSec = totalSeconds(item.getMedia());
            EpisodeAction.Builder builder = new EpisodeAction.Builder(item, EpisodeAction.Action.PLAY);
            builder.currentTimestamp();
            builder.started(0);
            builder.position(played ? totalSec : 0);
            builder.total(totalSec);
            EpisodeAction action = builder.build();
            database.enqueueSyncAction(action.getPodcast(), action.getEpisode(), action.getGuid(),
                    action.getAction().name(), action.getTimestamp().getTime(),
                    action.getStarted(), action.getPosition(), action.getTotal());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static final class SyncResult {
        public final int subscriptionsAdded;
        public final int actionsUploaded;
        public final int actionsApplied;
        public final List<Long> changedItemIds;
        public final List<Long> playedItemIds;
        public final List<Long> unplayedItemIds;
        public final List<Long> syncedItemIds = new ArrayList<>();

        SyncResult(int subscriptionsAdded, int actionsUploaded, int actionsApplied,
                List<Long> changedItemIds, List<Long> playedItemIds, List<Long> unplayedItemIds) {
            this.subscriptionsAdded = subscriptionsAdded;
            this.actionsUploaded = actionsUploaded;
            this.actionsApplied = actionsApplied;
            this.changedItemIds = List.copyOf(changedItemIds);
            this.playedItemIds = List.copyOf(playedItemIds);
            this.unplayedItemIds = List.copyOf(unplayedItemIds);
        }
    }
}
