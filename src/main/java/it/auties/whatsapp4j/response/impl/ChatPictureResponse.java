package it.auties.whatsapp4j.response.impl;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import it.auties.whatsapp4j.response.model.JsonResponseModel;


import java.util.Objects;

/**
 * A json model that contains information about a requested profile picture
 *
 * @param status the http status code for the original request
 * @param url the url for the requested profile picture
 * @param tag a tag for this response
 */
public record ChatPictureResponse(int status,  String url,  String tag) implements JsonResponseModel {
    @JsonCreator
    public ChatPictureResponse( @JsonProperty("eurl") String url,  @JsonProperty("tag") String tag, @JsonProperty("status") Integer status){
        this(Objects.requireNonNullElse(status, 200), url, tag);
    }
}
