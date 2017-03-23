package org.appspot.apprtc;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.webrtc.IceCandidate;
import org.webrtc.PeerConnection;
import org.webrtc.SessionDescription;

import java.net.URISyntaxException;
import java.util.LinkedList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.socket.client.IO;
import io.socket.client.Socket;
import io.socket.emitter.Emitter;

/**
 * Created by yytang on 3/22/17.
 */

public class WebDirectRTCClient implements AppRTCClient {
  private static final String TAG = "WebDirectRTCClient";
  private static final int DEFAULT_PORT = 8888;
  private String host;
  private Socket client;
  private String from;

  // Regex pattern used for checking if room id looks like an IP.
  static final Pattern IP_PATTERN = Pattern.compile("("
      // IPv4
      + "((\\d+\\.){3}\\d+)|"
      // IPv6
      + "\\[((([0-9a-fA-F]{1,4}:)*[0-9a-fA-F]{1,4})?::"
      + "(([0-9a-fA-F]{1,4}:)*[0-9a-fA-F]{1,4})?)\\]|"
      + "\\[(([0-9a-fA-F]{1,4}:){7}[0-9a-fA-F]{1,4})\\]|"
      // IPv6 without []
      + "((([0-9a-fA-F]{1,4}:)*[0-9a-fA-F]{1,4})?::(([0-9a-fA-F]{1,4}:)*[0-9a-fA-F]{1,4})?)|"
      + "(([0-9a-fA-F]{1,4}:){7}[0-9a-fA-F]{1,4})|"
      // Literals
      + "localhost"
      + ")"
      // Optional port number
      + "(:(\\d+))?");

  private final ExecutorService executor;
  private final SignalingEvents events;
  private TCPChannelClient tcpClient;
  private RoomConnectionParameters connectionParameters;

  private enum ConnectionState { NEW, CONNECTED, CLOSED, ERROR }

  // All alterations of the room state should be done from inside the looper thread.
  private ConnectionState roomState;

  public WebDirectRTCClient(SignalingEvents events) {
    this.events = events;

    executor = Executors.newSingleThreadExecutor();
    roomState = ConnectionState.NEW;
  }

  /**
   * Connects to the room, roomId in connectionsParameters is required. roomId must be a valid
   * IP address matching IP_PATTERN.
   */
  @Override
  public void connectToRoom(RoomConnectionParameters connectionParameters) {
    this.connectionParameters = connectionParameters;

    if (connectionParameters.loopback) {
      reportError("Loopback connections aren't supported by DirectRTCClient.");
    }

    executor.execute(new Runnable() {
      @Override
      public void run() {
        connectToRoomInternal();
      }
    });
  }

  @Override
  public void disconnectFromRoom() {
    executor.execute(new Runnable() {
      @Override
      public void run() {
        disconnectFromRoomInternal();
      }
    });
  }

  /**
   * Connects to the room.
   *
   * Runs on the looper thread.
   */
  private void connectToRoomInternal() {
    this.roomState = ConnectionState.NEW;

    String endpoint = connectionParameters.roomId;

    Matcher matcher = IP_PATTERN.matcher(endpoint);
    if (!matcher.matches()) {
      reportError("roomId must match IP_PATTERN for DirectRTCClient.");
      return;
    }

    String ip = matcher.group(1);
    String portStr = matcher.group(matcher.groupCount());
    int port;

    if (portStr != null) {
      try {
        port = Integer.parseInt(portStr);
      } catch (NumberFormatException e) {
        reportError("Invalid port number: " + portStr);
        return;
      }
    } else {
      port = DEFAULT_PORT;
    }

    MessageHandler messageHandler = new MessageHandler("yytang_test");
    host = "http://" + ip + ":" + port + "/";
    try {
        client = IO.socket(host);
    } catch (URISyntaxException e) {
        e.printStackTrace();
    }
    client.on("id", messageHandler.onId);
    client.on("message", messageHandler.onMessage);
    client.connect();
//    tcpClient = new TCPChannelClient(executor, this, ip, port);
  }

  /**
   * Disconnects from the room.
   *
   * Runs on the looper thread.
   */
  private void disconnectFromRoomInternal() {
    roomState = ConnectionState.CLOSED;

    if (client != null) {
      client.disconnect();
      client.close();
      client = null;
    }
    executor.shutdown();
  }

  private class MessageHandler {
    String name;
    private MessageHandler(String name) {
      Log.i(TAG, "Message Handler");
      this.name = name;
    }

    private Emitter.Listener onId = new Emitter.Listener() {
      @Override
      public void call(Object... args) {
        String id = args[0].toString();
        Log.i(TAG, "id = " + id);
        try {
          JSONObject message = new JSONObject();
          message.put("name", name);
          client.emit("readyToStream", message);
        } catch (JSONException e) {
          e.printStackTrace();
        }
      }
    };

    private Emitter.Listener onMessage = new Emitter.Listener() {
      @Override
      public void call(Object... args) {
        JSONObject json = (JSONObject) args[0];
        Log.i(TAG, "data = " + json.toString());
        try {
          from = json.getString("from");
          String type = json.getString("type");
          JSONObject payload = null;
          if (!type.equals("init")) {
            payload = json.getJSONObject("payload");
          }
          if (type.equals("candidate")) {
            events.onRemoteIceCandidate(toJavaCandidate(payload));
          } else if (type.equals("remove-candidates")) {
            JSONArray candidateArray = payload.getJSONArray("candidates");
            IceCandidate[] candidates = new IceCandidate[candidateArray.length()];
            for (int i = 0; i < candidateArray.length(); ++i) {
              candidates[i] = toJavaCandidate(candidateArray.getJSONObject(i));
            }
            events.onRemoteIceCandidatesRemoved(candidates);
          } else if (type.equals("answer")) {
            SessionDescription sdp = new SessionDescription(
                SessionDescription.Type.fromCanonicalForm(type), payload.getString("sdp"));
            events.onRemoteDescription(sdp);
          } else if (type.equals("offer")) {
            SessionDescription sdp = new SessionDescription(
                SessionDescription.Type.fromCanonicalForm(type), payload.getString("sdp"));

            SignalingParameters parameters = new SignalingParameters(
                // Ice servers are not needed for direct connections.
                new LinkedList<PeerConnection.IceServer>(),
                false, // This code will only be run on the client side. So, we are not the initiator.
                null, // clientId
                null, // wssUrl
                null, // wssPostUrl
                sdp, // offerSdp
                null // iceCandidates
                );
            roomState = ConnectionState.CONNECTED;
            events.onConnectedToRoom(parameters);
          } else if (type.equals("init")) {
            SignalingParameters parameters = new SignalingParameters(
                // Ice servers are not needed for direct connections.
                new LinkedList<PeerConnection.IceServer>(),
                true, // Client side acts as the initiator on WebDirectRTCClient connections.
                null, // clientId
                null, // wssUrl
                null, // wwsPostUrl
                null, // offerSdp
                null // iceCandidates
                );
            roomState = ConnectionState.CONNECTED;
            events.onConnectedToRoom(parameters);
          }
          else {
            reportError("Unexpected TCP message: " + json);
          }
        } catch (JSONException e) {
          e.printStackTrace();
        }
      }
    };
  }

  @Override
  public void sendOfferSdp(final SessionDescription sdp) {
    executor.execute(new Runnable() {
      @Override
      public void run() {
        if (roomState != ConnectionState.CONNECTED) {
          reportError("Sending offer SDP in non connected state.");
          return;
        }
        JSONObject json = new JSONObject();
        jsonPut(json, "sdp", sdp.description);
        jsonPut(json, "type", "offer");
        sendMessage(json);
      }
    });
  }

  @Override
  public void sendAnswerSdp(final SessionDescription sdp) {
    executor.execute(new Runnable() {
      @Override
      public void run() {
        JSONObject json = new JSONObject();
        jsonPut(json, "sdp", sdp.description);
        jsonPut(json, "type", "answer");
        sendMessage(json);
      }
    });
  }

  @Override
  public void sendLocalIceCandidate(final IceCandidate candidate) {
    executor.execute(new Runnable() {
      @Override
      public void run() {
        JSONObject json = new JSONObject();
        jsonPut(json, "type", "candidate");
        jsonPut(json, "label", candidate.sdpMLineIndex);
        jsonPut(json, "id", candidate.sdpMid);
        jsonPut(json, "candidate", candidate.sdp);

        if (roomState != ConnectionState.CONNECTED) {
          reportError("Sending ICE candidate in non connected state.");
          return;
        }
        sendMessage(json);
      }
    });
  }

  /** Send removed Ice candidates to the other participant. */
  @Override
  public void sendLocalIceCandidateRemovals(final IceCandidate[] candidates) {
    executor.execute(new Runnable() {
      @Override
      public void run() {
        JSONObject json = new JSONObject();
        jsonPut(json, "type", "remove-candidates");
        JSONArray jsonArray = new JSONArray();
        for (final IceCandidate candidate : candidates) {
          jsonArray.put(toJsonCandidate(candidate));
        }
        jsonPut(json, "candidates", jsonArray);

        if (roomState != ConnectionState.CONNECTED) {
          reportError("Sending ICE candidate removals in non connected state.");
          return;
        }
        sendMessage(json);
      }
    });
  }

   public void startToOffer() {
    roomState = ConnectionState.CONNECTED;

    SignalingParameters parameters = new SignalingParameters(
        // Ice servers are not needed for direct connections.
        new LinkedList<PeerConnection.IceServer>(),
        true, // Client side acts as the initiator on WebDirectRTCClient connections.
        null, // clientId
        null, // wssUrl
        null, // wwsPostUrl
        null, // offerSdp
        null // iceCandidates
        );
    events.onConnectedToRoom(parameters);
  }

  // --------------------------------------------------------------------
  // Helper functions.
  private void reportError(final String errorMessage) {
    Log.e(TAG, errorMessage);
    executor.execute(new Runnable() {
      @Override
      public void run() {
        if (roomState != ConnectionState.ERROR) {
          roomState = ConnectionState.ERROR;
          events.onChannelError(errorMessage);
        }
      }
    });
  }

  /**
   * Send a message through the signaling server
   *
   * @param payload payload of message
   * @throws JSONException
   */
  public void sendMessage(JSONObject payload) {
    String type = null;
    try {
      type = payload.get("type").toString();
      Log.i("yytang", "type = " + type);
      JSONObject message = new JSONObject();
      message.put("to", from);
      message.put("type", type);
      message.put("payload", payload);
      client.emit("message", message);
    } catch (JSONException e) {
      e.printStackTrace();
    }
  }

  // Put a |key|->|value| mapping in |json|.
  private static void jsonPut(JSONObject json, String key, Object value) {
    try {
      json.put(key, value);
    } catch (JSONException e) {
      throw new RuntimeException(e);
    }
  }

  // Converts a Java candidate to a JSONObject.
  private static JSONObject toJsonCandidate(final IceCandidate candidate) {
    JSONObject json = new JSONObject();
    jsonPut(json, "label", candidate.sdpMLineIndex);
    jsonPut(json, "id", candidate.sdpMid);
    jsonPut(json, "candidate", candidate.sdp);
    return json;
  }

  // Converts a JSON candidate to a Java object.
  private static IceCandidate toJavaCandidate(JSONObject json) throws JSONException {
    return new IceCandidate(
        json.getString("id"), json.getInt("label"), json.getString("candidate"));
  }
}
