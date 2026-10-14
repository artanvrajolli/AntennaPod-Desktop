package de.danoeh.antennapod.desktop;

import de.danoeh.antennapod.model.feed.FeedItem;
import javafx.collections.ListChangeListener;
import javafx.geometry.Bounds;
import javafx.geometry.Orientation;
import javafx.scene.Node;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollBar;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;

/** A fixed episode bookmark over the scrollbar track; mouse input passes through it. */
final class EpisodeScrollMarker extends StackPane {
    private final ListView<FeedItem> list;
    private final Region marker = new Region();
    private final Tooltip tooltip = new Tooltip();
    private ScrollBar bar;
    private Node track;
    private boolean tooltipInstalled;
    private long lastMediaId = -1;
    private boolean playing;
    private int index = -1;

    EpisodeScrollMarker(ListView<FeedItem> list) {
        this.list = list;
        marker.getStyleClass().add("episode-scroll-marker");
        marker.setManaged(false);
        marker.setMouseTransparent(true);
        marker.setVisible(false);
        getChildren().addAll(list, marker);
        list.getItems().addListener((ListChangeListener<FeedItem>) change -> updateTarget());
        list.skinProperty().addListener((obs, was, current) -> {
            unbindTrack();
            requestLayout();
        });
        updateTarget();
    }

    void setPlayback(long mediaId, boolean playing) {
        if (mediaId >= 0) {
            lastMediaId = mediaId;
        }
        this.playing = mediaId >= 0 && playing;
        updateTarget();
    }

    private void updateTarget() {
        int current = DesktopApp.indexOfMedia(list.getItems(), lastMediaId);
        index = current >= 0 ? current : DesktopApp.indexOfLastPlayed(list.getItems());
        boolean active = current >= 0 && playing;
        marker.getStyleClass().remove("episode-scroll-marker-playing");
        if (active) {
            marker.getStyleClass().add("episode-scroll-marker-playing");
        }
        String title = index >= 0 ? list.getItems().get(index).getTitle() : "";
        String text = active ? Messages.format("episodes.scroll.playing", title != null ? title : "")
                : Messages.format("episodes.scroll.last_played", title != null ? title : "");
        tooltip.setText(text);
        marker.setAccessibleText(text);
        requestLayout();
    }

    private void bindTrack() {
        if (bar != null) {
            return;
        }
        for (Node node : list.lookupAll(".scroll-bar")) {
            if (node instanceof ScrollBar found && found.getOrientation() == Orientation.VERTICAL) {
                Node foundTrack = found.lookup(".track");
                if (foundTrack != null) {
                    bar = found;
                    track = foundTrack;
                    break;
                }
            }
        }
    }

    private void unbindTrack() {
        if (bar != null) {
            Tooltip.uninstall(bar, tooltip);
        }
        tooltipInstalled = false;
        bar = null;
        track = null;
    }

    @Override
    protected void layoutChildren() {
        super.layoutChildren();
        // The virtual flow decides scrollbar visibility during its own layout.
        list.layout();
        bindTrack();
        boolean show = index >= 0 && bar != null && bar.isVisible() && track != null
                && list.getScene() != null;
        if (show != tooltipInstalled && bar != null) {
            if (show) {
                Tooltip.install(bar, tooltip);
            } else {
                Tooltip.uninstall(bar, tooltip);
            }
            tooltipInstalled = show;
        }
        marker.setVisible(show);
        if (!show) {
            return;
        }
        Bounds bounds = sceneToLocal(track.localToScene(track.getBoundsInLocal()));
        double height = Math.min(5, bounds.getHeight());
        double y = bounds.getMinY() + trackFraction(index, list.getItems().size()) * bounds.getHeight();
        y = Math.max(bounds.getMinY(), Math.min(bounds.getMaxY() - height, y - height / 2));
        marker.resizeRelocate(bounds.getMinX(), y, bounds.getWidth(), height);
    }

    /** The center of the episode's share of the complete list, independent of scroll offset. */
    static double trackFraction(int index, int count) {
        return count > 0 ? Math.max(0, Math.min(1, (index + 0.5) / count)) : 0;
    }
}
