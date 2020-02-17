package ch.loway.oss.ari4java;

import ch.loway.oss.ari4java.generated.actions.requests.EventsEventWebsocketGetRequest;
import ch.loway.oss.ari4java.tools.ARIException;
import ch.loway.oss.ari4java.generated.actions.*;
import ch.loway.oss.ari4java.generated.models.*;
import ch.loway.oss.ari4java.tools.AriCallback;

import java.io.IOException;
import java.net.URL;

import ch.loway.oss.ari4java.tools.BaseAriAction;
import ch.loway.oss.ari4java.tools.MessageQueue;
import ch.loway.oss.ari4java.tools.HttpClient;
import ch.loway.oss.ari4java.tools.RestException;
import ch.loway.oss.ari4java.tools.WsClient;
import ch.loway.oss.ari4java.tools.http.NettyHttpClient;
import ch.loway.oss.ari4java.tools.tags.EventSource;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URLConnection;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ARI factory and helper class
 *
 * @author lenz
 * @author mwalton
 */
public class ARI {

    public final static Charset ENCODING = StandardCharsets.UTF_8;
    private final static String ALLOWED_IN_UID = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ";

    private String appName = "";
    private String url = "";
    private AriVersion version;
    private HttpClient httpClient;
    private WsClient wsClient;
    private ActionEvents liveActionEvent = null;
    private AriSubscriber subscriptions = new AriSubscriber();
    private final CopyOnWriteArrayList<BaseAriAction> liveActionList = new CopyOnWriteArrayList<>();

    public void setHttpClient(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    public void setWsClient(WsClient wsClient) {
        this.wsClient = wsClient;
    }

    public void setVersion(AriVersion version) {
        this.version = version;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    /**
     * Returns the current ARI version.
     *
     * @return the ARI version currently used.
     */
    public AriVersion getVersion() {
        return version;
    }

    /**
     * Returns the server and port the websocket is connected to.
     *
     * @return the server currently being used.
     */
    public String getUrl() {
        return url;
    }

    /**
     * Get the implementation for a given action interface
     *
     * @param klazz the required action interface class
     * @param <T>   interface class
     * @return An implementation instance
     * @throws ARIException when error
     */
    @SuppressWarnings("unchecked")
    public <T> T getActionImpl(Class<T> klazz) throws ARIException {

        // use the events method as we ref it for cleanup
        if (klazz == ActionEvents.class) {
            return (T) events();
        }

        BaseAriAction action = (BaseAriAction) buildConcreteImplementation(klazz);
        action.setHttpClient(this.httpClient);
        action.setWsClient(this.wsClient);
        action.setLiveActionList(this.liveActionList);
        return (T) action;
    }

    /**
     * Get the implementation for a given model interface
     *
     * @param klazz the required model interface class
     * @param <T>   interface class
     * @return An implementation instance
     * @throws ARIException when error
     */
    @SuppressWarnings("unchecked")
    public <T> T getModelImpl(Class<T> klazz) throws ARIException {
        return (T) buildConcreteImplementation(klazz);
    }

    /**
     * Builds a concrete instance given an interface.
     * Note that we make no assumptions on the type of objects being built.
     *
     * @param klazz the class
     * @return the concrete implementation for that interface under the ARI in use.
     * @throws ARIException when error
     */
    private Object buildConcreteImplementation(Class klazz) throws ARIException {

        if (version == null) {
            throw new ARIException("API version not set");
        }

        Class concrete = version.builder.getClassFactory().getImplementationFor(klazz);
        if (concrete == null) {
            throw new ARIException("No concrete implementation in " + version.name() + " for " + klazz);
        }

        try {
            return concrete.newInstance();
        } catch (IllegalAccessException e) {
            e.printStackTrace();
        } catch (InstantiationException e) {
            e.printStackTrace();
        } catch (NullPointerException e) {
            // Do nothing
            e.printStackTrace();
        }
        throw new ARIException("Unable to build concrete implementation "
                + "for " + klazz.getName()
                + " in " + version.name()
        );
    }

    /**
     * Close an action object that is open for WebSocket interaction
     *
     * @param action the action object
     * @throws ARIException when error
     */
    public void closeAction(Object action) throws ARIException {
        if (!(action instanceof BaseAriAction)) {
            throw new ARIException("Class " + action.getClass().getName() + " is not an Action implementation");
        }
        BaseAriAction ba = (BaseAriAction) action;
        try {
            ba.disconnectWs();
        } catch (RestException e) {
            throw new ARIException(e.getMessage());
        }
    }

    /**
     * Builds a connector object for the specified ARI version.
     * If the version is set as IM_FEELING_LUCKY, then it will first try connecting,
     * will detect the current ARI version and will then connect to it.
     * This method uses Netty for both websocket and HTTP.
     * <p>
     * As this sets everything up but does not do anything, we do not have any
     * information on whether this connection is valid or not.
     *
     * @param url     The URL of the Asterisk web server, e.g. http://10.10.5.8:8088/ - defined in http.conf
     * @param app     The app
     * @param user    The user name (defined in ari.conf)
     * @param pass    The password
     * @param version The required version
     * @return a connection object
     * @throws ARIException If the url is invalid, or the version of ARI is not supported.
     */
    public static ARI build(String url, String app, String user, String pass, AriVersion version) throws ARIException {
        if (version == AriVersion.IM_FEELING_LUCKY) {
            AriVersion currentVersion = detectAriVersion(url, user, pass);
            return build(url, app, user, pass, currentVersion);
        } else {
            try {
                return AriFactory.nettyHttp(url, user, pass, version, app);
            } catch (URISyntaxException e) {
                throw new ARIException("Wrong URI format: " + url);
            }
        }
    }

    /**
     * Sets the application name.
     *
     * @param s app name
     */
    public void setAppName(String s) {
        this.appName = s;
    }

    /**
     * Return the current application name.
     *
     * @return the appName
     */
    public String getAppName() {
        return this.appName;
    }

    /**
     * Connect and detect the current ARI version.
     * If the ARI version is not supported,
     * will raise an excepttion as we have no bindings for it.
     *
     * @param url  url
     * @param user user
     * @param pass pass
     * @return the version of your server
     * @throws ARIException if the version is not supported
     */
    protected static AriVersion detectAriVersion(String url, String user, String pass) throws ARIException {

        String response = doHttpGet(url + "ari/api-docs/resources.json", user, pass);
        String version = findVersionString(response);
        return AriVersion.fromVersionString(version);

    }

    /**
     * Runs an HTTP GET and returns the text downloaded.
     * <p>
     * \TODO does it really belong here?
     *
     * @param urlWithParms urlWithParms
     * @param user         user
     * @param pwd          pwd
     * @return The body of the HTTP request.
     * @throws ARIException when error
     */
    private static String doHttpGet(String urlWithParms, String user, String pwd) throws ARIException {
        URL url = null;
        try {
            url = new URL(urlWithParms);
        } catch (MalformedURLException e) {
            throw new ARIException("MalformedUrlException: " + e.getMessage());
        }

        URLConnection uc = null;
        try {
            uc = url.openConnection();
        } catch (IOException e) {
            throw new ARIException("IOException: " + e.getMessage());
        }

        StringBuilder response = new StringBuilder();

        String userpass = user + ":" + pwd;
        String basicAuth = "Basic " + javax.xml.bind.DatatypeConverter.printBase64Binary(userpass.getBytes(ARI.ENCODING));

        uc.setRequestProperty("Authorization", basicAuth);
        InputStream is = null;
        try {
            is = uc.getInputStream();
        } catch (IOException e) {
            throw new ARIException("Cannot connect: " + e.getMessage());
        }


        BufferedReader buffReader = new BufferedReader(new InputStreamReader(is, ARI.ENCODING));

        String line = null;
        try {
            line = buffReader.readLine();
        } catch (IOException e) {
            throw new ARIException("IOException: " + e.getMessage());
        }
        while (line != null) {
            response.append(line);
            response.append('\n');
            try {
                line = buffReader.readLine();
            } catch (IOException e) {
                throw new ARIException("IOException: " + e.getMessage());
            }
        }
        try {
            buffReader.close();
        } catch (IOException e) {
            throw new ARIException("IOException: " + e.getMessage());
        }

        //System.out.println("Response: " + response.toString());
        return response.toString();
    }

    /**
     * Matches the version string out of the resources.json file.
     *
     * @param response res
     * @return a String describing the version reported from Asterisk.
     * @throws ARIException when error
     */
    private static String findVersionString(String response) throws ARIException {
        Pattern p = Pattern.compile(".apiVersion.:\\s*\"(.+?)\"", Pattern.MULTILINE + Pattern.CASE_INSENSITIVE);

        Matcher m = p.matcher(response);
        if (m.find()) {
            return m.group(1);
        } else {
            throw new ARIException("Could not match apiVersion ");
        }
    }

    /**
     * This operation is the opposite of a build() - to be called in the final
     * clause where the ARI object is built.
     * <p>
     * In any case, it is good practice to have a way to deallocate stuff like
     * the websocket or any circular reference.
     */
    public void cleanup() {
        try {
            unsubscribeApplication();
        } catch (ARIException e) {
            // ignore on cleanup...
        }
        for (BaseAriAction liveAction : liveActionList) {
            try {
                closeAction(liveAction);
            } catch (ARIException e) {
                // ignore on cleanup...
            }
        }

        destroy(wsClient);
        if (wsClient != httpClient) {
            destroy(httpClient);
        }

        wsClient = null;
        httpClient = null;
    }

    /**
     * unsubscribe from all resources of the stasis application
     *
     * @throws RestException when error
     */
    private void unsubscribeApplication() throws RestException {
        Application application = applications().get(appName).execute();
        // unsubscribe from all channels
        for (int i = 0; i < application.getChannel_ids().size(); i++) {
            applications().unsubscribe(appName, "channel:" + application.getChannel_ids().get(i)).execute();
        }
        // unsubscribe from all bridges
        for (int i = 0; i < application.getBridge_ids().size(); i++) {
            applications().unsubscribe(appName, "bridge:" + application.getBridge_ids().get(i)).execute();
        }
        // unsubscribe from all endpoints
        for (int i = 0; i < application.getEndpoint_ids().size(); i++) {
            applications().unsubscribe(appName, "endpoint:" + application.getEndpoint_ids().get(i)).execute();
        }
        // unsubscribe from all deviceState
        for (int i = 0; i < application.getDevice_names().size(); i++) {
            applications().unsubscribe(appName, "deviceState:" + application.getDevice_names().get(i)).execute();
        }
    }

    /**
     * Does the destruction of a client. In a sense, it is a reverse factory.
     *
     * @param client the client object
     * @throws IllegalArgumentException All clients should be of a known type. Let's play it safe.
     */
    private void destroy(Object client) {
        if (client != null) {
            if (client instanceof NettyHttpClient) {
                NettyHttpClient nhc = (NettyHttpClient) client;
                nhc.destroy();
            } else {
                throw new IllegalArgumentException("Unknown client object " + client);
            }
        }
    }

    public MessageQueue getWebsocketQueue() throws ARIException {
        return getWebsocketQueue(false);
    }

    /**
     * In order to avoid multi-threading for users, you can get a
     * MessageQueue object and poll on it for new messages.
     * This makes sure you don't really need to synchonize or be worried by
     * threading issues
     *
     * @param subscribeAll subscribe to all events
     * @return The MQ connected to your websocket.
     * @throws ARIException when error
     */
    public MessageQueue getWebsocketQueue(boolean subscribeAll) throws ARIException {

        if (liveActionEvent != null) {
            throw new ARIException("Websocket already present");
        }

        final MessageQueue q = new MessageQueue();

        AriCallback callback = new AriCallback<Message>() {

            @Override
            public void onSuccess(Message result) {
                q.queue(result);
            }

            @Override
            public void onFailure(RestException e) {
                q.queueError("Err:" + e.getMessage());
            }
        };

        EventsEventWebsocketGetRequest eventsRequest = events().eventWebsocket(appName);
        try {
            eventsRequest.setSubscribeAll(subscribeAll);
        } catch (UnsupportedOperationException e) {
            // ignore
        }
        eventsRequest.execute(callback);

        return q;

    }

    /**
     * Gets us a ready to use object.
     *
     * @return an Applications object.
     */
    public ActionApplications applications() {
        return (ActionApplications) setupAction(version.builder().actionApplications());
    }

    /**
     * Gets us a ready to use object.
     *
     * @return an Asterisk object.
     */
    public ActionAsterisk asterisk() {
        return (ActionAsterisk) setupAction(version.builder().actionAsterisk());
    }

    /**
     * Gets us a ready to use object.
     *
     * @return a Bridges object.
     */
    public ActionBridges bridges() {
        return (ActionBridges) setupAction(version.builder().actionBridges());
    }

    /**
     * Gets us a ready to use object.
     *
     * @return a Channels object.
     */
    public ActionChannels channels() {
        return (ActionChannels) setupAction(version.builder().actionChannels());
    }

    /**
     * Gets us a ready to use object.
     *
     * @return a DeviceStates object.
     */
    public ActionDeviceStates deviceStates() {
        return (ActionDeviceStates) setupAction(version.builder().actionDeviceStates());
    }

    /**
     * Gets us a ready to use object.
     *
     * @return an Endpoints object.
     */
    public ActionEndpoints endpoints() {
        return (ActionEndpoints) setupAction(version.builder().actionEndpoints());
    }

    /**
     * Gets us a ready to use object.
     *
     * @return an Events object.
     */
    public ActionEvents events() {
        liveActionEvent = (ActionEvents) setupAction(version.builder().actionEvents());
        return liveActionEvent;
    }

    /**
     * Gets us a ready to use object.
     *
     * @return a Mailboxes object.
     */
    public ActionMailboxes mailboxes() {
        return (ActionMailboxes) setupAction(version.builder().actionMailboxes());
    }

    /**
     * Gets us a ready to use object.
     *
     * @return a Playbacks object.
     */
    public ActionPlaybacks playbacks() {
        return (ActionPlaybacks) setupAction(version.builder().actionPlaybacks());
    }

    /**
     * Gets us a ready to use object.
     *
     * @return a Recordings object.
     */
    public ActionRecordings recordings() {
        return (ActionRecordings) setupAction(version.builder().actionRecordings());
    }

    /**
     * Gets us a ready to use object.
     *
     * @return a Sounds object.
     */
    public ActionSounds sounds() {
        return (ActionSounds) setupAction(version.builder().actionSounds());
    }

    /**
     * This code REALLY smells bad.
     * Most likely we should either implement an interface, or push the clients
     * to the default builder.
     * <p>
     * See the getActionImpl() method here.
     * <p>
     * \TODO
     *
     * @param a the object
     * @return an Action object on which we'll set the default clients.
     * @throws IllegalArgumentException when error
     */
    public Object setupAction(Object a) throws IllegalArgumentException {
        if (a instanceof BaseAriAction) {
            BaseAriAction action = (BaseAriAction) a;
            action.setHttpClient(this.httpClient);
            action.setWsClient(this.wsClient);
            action.setLiveActionList(this.liveActionList);
        } else {
            throw new IllegalArgumentException("Object does not seem to be an Action implementation " + a.toString());
        }
        return a;
    }

    /**
     * Wrapper of the Thread.sleep() to avoid exception.
     *
     * @param ms how long is it going to sleep.
     */
    public static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            System.err.println("Interrupted: " + e.getMessage());
        }
    }

    /**
     * Generates a pseudo-random ID like "a4j.ZH6IA.IXEX0.TUIE8".
     *
     * @return the UID
     */
    public static String getUID() {
        StringBuilder sb = new StringBuilder(20);

        sb.append("a4j");

        for (int n = 0; n < 15; n++) {
            if ((n % 5) == 0) {
                sb.append(".");
            }
            int pos = (int) (Math.random() * ALLOWED_IN_UID.length());
            sb.append(ALLOWED_IN_UID.charAt(pos));
        }
        return sb.toString();

    }

    /**
     * Subscribes to an event source.
     *
     * @param m event
     * @return the Application object
     * @throws RestException when error
     */
    public Application subscribe(EventSource m) throws RestException {
        return subscriptions.subscribe(this, m);
    }

    /**
     * Unsubscribes from an event source.
     *
     * @param m event
     * @throws RestException when error
     */
    public void unsubscribe(EventSource m) throws RestException {
        subscriptions.unsubscribe(this, m);

    }

    /**
     * Unsubscribes from all known subscriptions.
     *
     * @throws RestException when error
     */
    public void unsubscribeAll() throws RestException {
        subscriptions.unsubscribeAll(this);
    }

    /**
     * This interface is used to go from an interface to its concrete
     * implementation.
     */
    public static interface ClassFactory {
        public Class getImplementationFor(Class interfaceClass);
    }
}
