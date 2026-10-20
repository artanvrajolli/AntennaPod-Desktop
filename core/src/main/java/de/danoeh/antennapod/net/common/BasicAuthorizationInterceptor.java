package de.danoeh.antennapod.net.common;

import android.text.TextUtils;
import android.util.Log;
import androidx.annotation.NonNull;
import de.danoeh.antennapod.model.download.DownloadRequest;
import de.danoeh.antennapod.net.common.HttpCredentialEncoder;
import de.danoeh.antennapod.net.common.UriUtil;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.util.List;

import okhttp3.HttpUrl;
import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.Response;

public class BasicAuthorizationInterceptor implements Interceptor {
    private static final String TAG = "BasicAuthInterceptor";
    private static final String HEADER_AUTHORIZATION = "Authorization";

    /**
     * Desktop: requests carry no DownloadRequest tag, so a challenged request asks this for the
     * "user:pass" of its URL instead (see FeedCredentials). Null when there is none.
     */
    private static volatile java.util.function.Function<okhttp3.HttpUrl, String> credentialLookup;

    public static void setCredentialLookup(java.util.function.Function<okhttp3.HttpUrl, String> lookup) {
        credentialLookup = lookup;
    }

    @Override
    @NonNull
    public Response intercept(Chain chain) throws IOException {
        Request request = chain.request();

        Response response = chain.proceed(request);

        if (response.code() != HttpURLConnection.HTTP_UNAUTHORIZED) {
            return response;
        }

        Request.Builder newRequest = request.newBuilder();
        HttpUrl originalUrl = request.url();
        HttpUrl redirectedUrl = response.request().url();
        if (!TextUtils.equals(redirectedUrl.toString(), originalUrl.toString())) {
            // Redirect detected. OkHTTP does not re-add the headers on redirect, so calling the new location directly.
            newRequest.url(redirectedUrl);

            boolean sameOrigin = redirectedUrl.host().equalsIgnoreCase(originalUrl.host())
                    && redirectedUrl.port() == originalUrl.port()
                    && redirectedUrl.scheme().equalsIgnoreCase(originalUrl.scheme());
            List<String> authorizationHeaders = request.headers().values(HEADER_AUTHORIZATION);
            if (sameOrigin && !authorizationHeaders.isEmpty()
                    && !TextUtils.isEmpty(authorizationHeaders.get(0))) {
                // Same host, port and scheme: retry with the credentials already supplied.
                // Never replay them to another origin, which is what OkHttp strips them for.
                newRequest.header(HEADER_AUTHORIZATION, authorizationHeaders.get(0));
                response.close();
                return chain.proceed(newRequest.build());
            }
        }

        String userInfo = null;
        if (request.tag() instanceof DownloadRequest) {
            DownloadRequest downloadRequest = (DownloadRequest) request.tag();
            userInfo = UriUtil.getURIFromRequestUrl(downloadRequest.getSource()).getUserInfo();
            if (TextUtils.isEmpty(userInfo)
                    && (!TextUtils.isEmpty(downloadRequest.getUsername())
                        || !TextUtils.isEmpty(downloadRequest.getPassword()))) {
                userInfo = downloadRequest.getUsername() + ":" + downloadRequest.getPassword();
            }
        }

        java.util.function.Function<okhttp3.HttpUrl, String> lookup = credentialLookup;
        if (TextUtils.isEmpty(userInfo) && lookup != null) {
            // the host that challenged us: after a redirect that is the final location
            userInfo = lookup.apply(response.request().url());
        }

        if (TextUtils.isEmpty(userInfo)) {
            Log.d(TAG, "no credentials for '" + request.url() + "'");
            return response;
        }

        if (!userInfo.contains(":")) {
            Log.d(TAG, "Invalid credentials for '" + request.url() + "'");
            return response;
        }
        String username = userInfo.substring(0, userInfo.indexOf(':'));
        String password = userInfo.substring(userInfo.indexOf(':') + 1);

        Log.d(TAG, "Authorization failed, re-trying with ISO-8859-1 encoded credentials");
        newRequest.header(HEADER_AUTHORIZATION, HttpCredentialEncoder.encode(username, password, "ISO-8859-1"));
        response.close();
        response = chain.proceed(newRequest.build());

        if (response.code() != HttpURLConnection.HTTP_UNAUTHORIZED) {
            return response;
        }

        Log.d(TAG, "Authorization failed, re-trying with UTF-8 encoded credentials");
        newRequest.header(HEADER_AUTHORIZATION, HttpCredentialEncoder.encode(username, password, "UTF-8"));
        response.close();
        return chain.proceed(newRequest.build());
    }
}
