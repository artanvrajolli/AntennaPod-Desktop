package de.danoeh.antennapod.desktop;

import de.danoeh.antennapod.model.feed.PodcastPerson;
import de.danoeh.antennapod.model.feed.Soundbite;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Podcasting 2.0 people and soundbites as the JSON text they are stored in. */
final class PodcastExtras {
    private PodcastExtras() {
    }

    static String personsToJson(List<PodcastPerson> persons) {
        if (persons == null || persons.isEmpty()) {
            return null;
        }
        JSONArray array = new JSONArray();
        for (PodcastPerson person : persons) {
            JSONObject object = new JSONObject();
            object.put("name", person.name);
            object.put("role", person.role);
            if (person.href != null) {
                object.put("href", person.href);
            }
            if (person.img != null) {
                object.put("img", person.img);
            }
            array.put(object);
        }
        return array.toString();
    }

    static ArrayList<PodcastPerson> personsFromJson(String json) {
        if (json == null || json.isEmpty()) {
            return null;
        }
        ArrayList<PodcastPerson> persons = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(json);
            for (int i = 0; i < array.length(); i++) {
                JSONObject object = array.getJSONObject(i);
                persons.add(new PodcastPerson(object.optString("name"), object.optString("role"),
                        object.optString("href", null), object.optString("img", null)));
            }
        } catch (JSONException e) {
            return null;
        }
        return persons.isEmpty() ? null : persons;
    }

    static String soundbitesToJson(List<Soundbite> soundbites) {
        if (soundbites == null || soundbites.isEmpty()) {
            return null;
        }
        JSONArray array = new JSONArray();
        for (Soundbite soundbite : soundbites) {
            JSONObject object = new JSONObject();
            object.put("start", soundbite.startMs);
            object.put("duration", soundbite.durationMs);
            object.put("title", soundbite.title);
            array.put(object);
        }
        return array.toString();
    }

    static ArrayList<Soundbite> soundbitesFromJson(String json) {
        if (json == null || json.isEmpty()) {
            return null;
        }
        ArrayList<Soundbite> soundbites = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(json);
            for (int i = 0; i < array.length(); i++) {
                JSONObject object = array.getJSONObject(i);
                soundbites.add(new Soundbite(object.optInt("start"), object.optInt("duration"),
                        object.optString("title")));
            }
        } catch (JSONException e) {
            return null;
        }
        return soundbites.isEmpty() ? null : soundbites;
    }
}
