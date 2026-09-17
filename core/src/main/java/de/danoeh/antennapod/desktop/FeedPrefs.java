package de.danoeh.antennapod.desktop;

public final class FeedPrefs {
    public static final int USE_GLOBAL = -1;
    public static final int OFF = 0;
    public static final int ON = 1;

    public final long feedId;
    public float speed;
    public int autoDownload;
    public int autoDelete;
    public String includeFilter;
    public String excludeFilter;
    public int minDurationSec;
    public String sortCode;

    public FeedPrefs(long feedId) {
        this.feedId = feedId;
        this.speed = 0;
        this.autoDownload = USE_GLOBAL;
        this.autoDelete = USE_GLOBAL;
        this.includeFilter = "";
        this.excludeFilter = "";
        this.minDurationSec = -1;
        this.sortCode = "newest";
    }

    public boolean effectiveAutoDownload(boolean globalDefault) {
        return autoDownload == USE_GLOBAL ? globalDefault : autoDownload == ON;
    }

    public boolean effectiveAutoDelete(boolean globalDefault) {
        return autoDelete == USE_GLOBAL ? globalDefault : autoDelete == ON;
    }

    public float effectiveSpeed(float globalSpeed) {
        return speed > 0 ? speed : globalSpeed;
    }
}
