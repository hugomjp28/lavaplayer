package com.sedmelluq.discord.lavaplayer.source.youtube;

import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.tools.JsonBrowser;
import com.sedmelluq.discord.lavaplayer.tools.Units;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpClientTools;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterface;
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import com.sedmelluq.discord.lavaplayer.track.BasicAudioPlaylist;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.sedmelluq.discord.lavaplayer.source.youtube.YoutubeHttpContextFilter.PBJ_PARAMETER;
import static com.sedmelluq.discord.lavaplayer.tools.FriendlyException.Severity.COMMON;

public class DefaultYoutubePlaylistLoader implements YoutubePlaylistLoader {
  private static final String REQUEST_URL = "https://www.youtube.com/youtubei/v1/browse?key=AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8";
  private static final String REQUEST_PAYLOAD = "{\"context\":{\"client\":{\"clientName\":\"WEB\",\"clientVersion\":\"2.20210302.07.01\"}},\"continuation\":\"%s\"}";
  private volatile int playlistPageCount = 6;
  private static final String REQUEST_PLAYLIST = "https://youtube.googleapis.com/youtube/v3/playlistItems?part=snippet&key=AIzaSyAA1do2B4XuM6xXkxhGrBjBxj7npXmBlWM&playlistId=";
  private static final String REQUEST_PLAYLIST_PAGE = "&pageToken=";
  @Override
  public void setPlaylistPageCount(int playlistPageCount) {
    this.playlistPageCount = playlistPageCount;
  }

  @Override
  public AudioPlaylist load(HttpInterface httpInterface, String playlistId, String selectedVideoId,
                            Function<AudioTrackInfo, AudioTrack> trackFactory) {

    HttpGet request = new HttpGet(REQUEST_PLAYLIST + playlistId);

    try (CloseableHttpResponse response = httpInterface.execute(request)) {
      HttpClientTools.assertSuccessWithContent(response, "playlist response");
      HttpClientTools.assertJsonContentType(response);

      JsonBrowser json = JsonBrowser.parse(response.getEntity().getContent());
      return buildPlaylist(httpInterface, json, playlistId, selectedVideoId, trackFactory);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  private AudioPlaylist buildPlaylist(HttpInterface httpInterface, JsonBrowser json, String playlistId, String selectedVideoId,
                                      Function<AudioTrackInfo, AudioTrack> trackFactory) throws IOException {

    String playlistName = json.get("etag").text();

    JsonBrowser playlistVideoList = json
            .get("items");

    String nextPageToken = json
            .get("nextPageToken").text();

    List<AudioTrack> tracks = new ArrayList<>();
    String continuationsToken = extractPlaylistTracks(playlistVideoList, nextPageToken, tracks, trackFactory);
    int loadCount = 0;
    int pageCount = playlistPageCount;

    // Also load the next pages, each result gives us a JSON with separate values for list html and next page loader html
    while (continuationsToken != null && ++loadCount < pageCount) {
      HttpGet request = new HttpGet(REQUEST_PLAYLIST + playlistId + REQUEST_PLAYLIST_PAGE + continuationsToken);
      try (CloseableHttpResponse response = httpInterface.execute(request)) {
        HttpClientTools.assertSuccessWithContent(response, "playlist response");

        JsonBrowser continuationJson = JsonBrowser.parse(response.getEntity().getContent());

        nextPageToken = continuationJson
                .get("nextPageToken").text();

        JsonBrowser playlistVideoListPage = continuationJson.get("items");

        if (playlistVideoListPage.isNull()) {
          break;
        }

        continuationsToken = extractPlaylistTracks(playlistVideoListPage, nextPageToken, tracks, trackFactory);
      }
    }

    return new BasicAudioPlaylist(playlistName, tracks, findSelectedTrack(tracks, selectedVideoId), false);
  }

  /*
  private String findErrorAlert(JsonBrowser jsonResponse) {
    JsonBrowser alerts = jsonResponse.get("alerts");

    if (!alerts.isNull()) {
      for(JsonBrowser alert : alerts.values()) {
        JsonBrowser alertInner = alert.values().get(0);
        String type = alertInner.get("type").text();

        if("ERROR".equals(type)) {
          JsonBrowser textObject = alertInner.get("text");

          String text;
          if(!textObject.get("simpleText").isNull()) {
            text = textObject.get("simpleText").text();
          } else {
            text = textObject.get("runs").values().stream()
                    .map(run -> run.get("text").text())
                    .collect(Collectors.joining());
          }

          return text;
        }
      }
    }

    return null;
  }*/

  private AudioTrack findSelectedTrack(List<AudioTrack> tracks, String selectedVideoId) {
    if (selectedVideoId != null) {
      for (AudioTrack track : tracks) {
        if (selectedVideoId.equals(track.getIdentifier())) {
          return track;
        }
      }
    }

    return null;
  }

  private String extractPlaylistTracks(JsonBrowser playlistVideoList, String nextPageToken, List<AudioTrack> tracks,
                                       Function<AudioTrackInfo, AudioTrack> trackFactory) {

    if (playlistVideoList.isNull()) return null;

    final List<JsonBrowser> playlistTrackEntries = playlistVideoList.values();
    for (JsonBrowser track : playlistTrackEntries) {
      JsonBrowser item = track.get("snippet");

      // If the isPlayable property does not exist, it means the video is removed or private
      // If the shortBylineText property does not exist, it means the Track is Region blocked
      if (!item.get("resourceId").isNull()) {
        String videoId = item.get("resourceId").get("videoId").text();
        String title = item.get("title").text();
        String author = item.get("videoOwnerChannelTitle").text();
        long duration = 0;

        AudioTrackInfo info = new AudioTrackInfo(title, author, duration, videoId, false,
                "https://www.youtube.com/watch?v=" + videoId);

        tracks.add(trackFactory.apply(info));
      }
    }

    return nextPageToken;
  }

  /*
  private static String getPlaylistUrl(String playlistId) {
    return "https://www.youtube.com/playlist?list=" + playlistId;
  }*/
}