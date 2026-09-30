package de.danoeh.antennapod.parser.feed.namespace;

import android.text.TextUtils;
import de.danoeh.antennapod.parser.feed.HandlerState;
import de.danoeh.antennapod.parser.feed.element.SyndElement;
import org.xml.sax.Attributes;
import de.danoeh.antennapod.model.feed.FeedFunding;
import de.danoeh.antennapod.model.feed.FeedItem;
import de.danoeh.antennapod.model.feed.PodcastPerson;
import de.danoeh.antennapod.model.feed.Soundbite;

public class PodcastIndex extends Namespace {

    public static final String NSTAG = "podcast";
    public static final String NSURI = "https://github.com/Podcastindex-org/podcast-namespace/blob/main/docs/1.0.md";
    public static final String NSURI2 = "https://podcastindex.org/namespace/1.0";
    private static final String URL = "url";
    private static final String URI = "uri";
    private static final String FUNDING = "funding";
    private static final String CHAPTERS = "chapters";
    private static final String SOCIAL_INTERACT = "socialInteract";
    private static final String TRANSCRIPT = "transcript";
    private static final String TYPE = "type";
    private static final String PERSON = "person";
    private static final String SOUNDBITE = "soundbite";
    /** The attributes of a person or soundbite tag, kept until its text arrives at the end tag. */
    private static final String PENDING_ATTRIBUTES = "podcastIndex.pendingAttributes";

    @Override
    public SyndElement handleElementStart(String localName, HandlerState state,
                                          Attributes attributes) {
        if (PERSON.equals(localName)) {
            state.getTempObjects().put(PENDING_ATTRIBUTES, new String[]{
                    attributes.getValue("role"), attributes.getValue("href"), attributes.getValue("img")});
        } else if (SOUNDBITE.equals(localName)) {
            state.getTempObjects().put(PENDING_ATTRIBUTES, new String[]{
                    attributes.getValue("startTime"), attributes.getValue("duration")});
        } else if (FUNDING.equals(localName)) {
            String href = attributes.getValue(URL);
            FeedFunding funding = new FeedFunding(href, "");
            state.setCurrentFunding(funding);
            state.getFeed().addPayment(state.getCurrentFunding());
        } else if (CHAPTERS.equals(localName)) {
            String href = attributes.getValue(URL);
            if (!TextUtils.isEmpty(href)) {
                state.getCurrentItem().setPodcastIndexChapterUrl(href);
            }
        } else if (SOCIAL_INTERACT.equals(localName)) {
            String href = attributes.getValue(URI);
            if (!TextUtils.isEmpty(href) && state.getCurrentItem() != null) {
                state.getCurrentItem().setSocialInteractUrl(href);
            }
        } else if (TRANSCRIPT.equals(localName)) {
            String href = attributes.getValue(URL);
            String type = attributes.getValue(TYPE);
            if (!TextUtils.isEmpty(href) && !TextUtils.isEmpty(type)) {
                state.getCurrentItem().setTranscriptUrl(type, href);
            }
        }
        return new SyndElement(localName, this);
    }

    @Override
    public void handleElementEnd(String localName, HandlerState state) {
        if (PERSON.equals(localName) || SOUNDBITE.equals(localName)) {
            String[] pending = (String[]) state.getTempObjects().remove(PENDING_ATTRIBUTES);
            String text = state.getContentBuf() != null ? state.getContentBuf().toString().trim() : "";
            if (pending != null) {
                endPersonOrSoundbite(localName, pending, text, state);
            }
            return;
        }
        if (state.getContentBuf() == null) {
            return;
        }
        String content = state.getContentBuf().toString();
        if (FUNDING.equals(localName) && state.getCurrentFunding() != null && !TextUtils.isEmpty(content)) {
            state.getCurrentFunding().setContent(content);
        }
    }

    /** A person needs a name; a soundbite needs a start and a length (seconds, maybe fractional). */
    private static void endPersonOrSoundbite(String localName, String[] pending, String text,
                                             HandlerState state) {
        FeedItem item = state.getCurrentItem();
        if (PERSON.equals(localName)) {
            if (text.isEmpty()) {
                return;
            }
            PodcastPerson person = new PodcastPerson(text, pending[0], pending[1], pending[2]);
            if (item != null) {
                item.addPerson(person);
            } else if (state.getFeed() != null) {
                state.getFeed().addPerson(person);
            }
            return;
        }
        if (item == null) {
            return;
        }
        try {
            double start = Double.parseDouble(pending[0].trim());
            double duration = Double.parseDouble(pending[1].trim());
            if (start >= 0 && duration > 0) {
                item.addSoundbite(new Soundbite((int) Math.round(start * 1000),
                        (int) Math.round(duration * 1000), text));
            }
        } catch (NullPointerException | NumberFormatException e) {
            // a soundbite without a usable start or length is skipped, like a broken chapter
        }
    }
}
