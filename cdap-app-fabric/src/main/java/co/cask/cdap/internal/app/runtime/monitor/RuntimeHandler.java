/*
 * Copyright © 2018 Cask Data, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package co.cask.cdap.internal.app.runtime.monitor;

import co.cask.cdap.api.dataset.lib.CloseableIterator;
import co.cask.cdap.api.messaging.Message;
import co.cask.cdap.api.messaging.MessageFetcher;
import co.cask.cdap.api.messaging.TopicNotFoundException;
import co.cask.cdap.common.conf.CConfiguration;
import co.cask.cdap.common.logging.LogSamplers;
import co.cask.cdap.common.logging.Loggers;
import co.cask.cdap.proto.id.NamespaceId;
import co.cask.http.AbstractHttpHandler;
import co.cask.http.ChunkResponder;
import co.cask.http.HttpResponder;
import com.google.common.io.Closeables;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufInputStream;
import io.netty.buffer.ByteBufOutputStream;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponseStatus;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericDatumReader;
import org.apache.avro.generic.GenericDatumWriter;
import org.apache.avro.generic.GenericRecord;
import org.apache.avro.io.DatumReader;
import org.apache.avro.io.DatumWriter;
import org.apache.avro.io.Decoder;
import org.apache.avro.io.DecoderFactory;
import org.apache.avro.io.Encoder;
import org.apache.avro.io.EncoderFactory;
import org.apache.avro.util.Utf8;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.Optional;
import javax.annotation.Nullable;
import javax.ws.rs.POST;
import javax.ws.rs.Path;

/**
 * {@link co.cask.http.HttpHandler} for exposing metadata of a runtime.
 */
@Path("/v1/runtime")
public class RuntimeHandler extends AbstractHttpHandler {

  private static final Logger LOG = LoggerFactory.getLogger(RuntimeHandler.class);
  // For outage, only log once per 60 seconds per message.
  private static final Logger OUTAGE_LOG =  Loggers.sampling(LOG, LogSamplers.perMessage(
    () -> LogSamplers.limitRate(60000)));
  private static final int CHUNK_SIZE = 8192;

  private final CConfiguration cConf;
  private final MessageFetcher messageFetcher;
  private final Runnable shutdownRunnable;
  // caches request key to topic
  private final Map<String, String> requestKeyToLocalTopic;
  private final DatumWriter<GenericRecord> writer;

  public RuntimeHandler(CConfiguration cConf, MessageFetcher messageFetcher, Runnable shutdownRunnable) {
    this.cConf = cConf;
    this.messageFetcher = messageFetcher;
    this.shutdownRunnable = shutdownRunnable;
    this.requestKeyToLocalTopic = new HashMap<>();
    this.writer = new GenericDatumWriter<GenericRecord>(MonitorSchemas.V1.MonitorResponse.SCHEMA
                                                          .getValueType().getElementType()) {

      @Override
      protected void writeBytes(Object datum, Encoder out) throws IOException {
        if (datum instanceof byte[]) {
          out.writeBytes((byte[]) datum);
        } else {
          super.writeBytes(datum, out);
        }
      }
    };
  }

  /**
   * Gets list of topics along with offsets and limit as request and returns list of messages
   */
  @POST
  @Path("/metadata")
  public void metadata(FullHttpRequest request, HttpResponder responder) throws Exception {
    Map<Utf8, GenericRecord> consumeRequests = decodeConsumeRequest(request);
    ChunkResponder chunkResponder = responder.sendChunkStart(
            HttpResponseStatus.OK, new DefaultHttpHeaders().set(HttpHeaderNames.CONTENT_TYPE, "avro/binary"));
    ByteBuf buffer = Unpooled.buffer();

    // Encode message and send messages
    encodeAndSendResponse(consumeRequests, chunkResponder, buffer);

    if (buffer.isReadable()) {
      chunkResponder.sendChunk(buffer.copy());
    }

    Closeables.closeQuietly(chunkResponder);
  }

  /**
   * shuts down remote runtime
   */
  @POST
  @Path("/shutdown")
  public void shutdown(FullHttpRequest request, HttpResponder responder) throws Exception {
    responder.sendString(HttpResponseStatus.OK, "Triggering shutdown down Runtime Http Server.");
    shutdownRunnable.run();
  }

  /**
   * Decode consume request from avro binary format
   */
  private Map<Utf8, GenericRecord> decodeConsumeRequest(FullHttpRequest request) throws IOException {
    Decoder decoder = DecoderFactory.get().directBinaryDecoder(new ByteBufInputStream(request.content()), null);
    DatumReader<Map<Utf8, GenericRecord>> datumReader = new GenericDatumReader<>(
      MonitorSchemas.V1.MonitorConsumeRequest.SCHEMA);

    // Avro converts java String to UTF8 while deserializing strings.
    return datumReader.read(null, decoder);
  }

  /**
   * Encode response in avro binary format
   */
  private void encodeAndSendResponse(Map<Utf8, GenericRecord> consumeRequests, ChunkResponder chunkResponder,
                                     ByteBuf buffer) throws IOException {
    Encoder encoder = EncoderFactory.get().directBinaryEncoder(new ByteBufOutputStream(buffer), null);
    encoder.writeMapStart();
    encoder.setItemCount(consumeRequests.size());

    for (Map.Entry<Utf8, GenericRecord> entry : consumeRequests.entrySet()) {
      String topicConfig = String.valueOf(entry.getKey());
      encoder.startItem();
      encoder.writeString(topicConfig);
      encoder.writeArrayStart();

      if (!requestKeyToLocalTopic.containsKey(topicConfig)) {
        requestKeyToLocalTopic.put(topicConfig, getTopic(topicConfig));
      }

      String topic = requestKeyToLocalTopic.get(topicConfig);

      try {
        int limit = (int) entry.getValue().get("limit");
        Optional<String> messageId = Optional.ofNullable(String.valueOf(entry.getValue().get("messageId")))
          .filter(s -> !s.equals("null"));

        fetchAndWriteMessages(encoder, buffer, chunkResponder, topic, limit, messageId.orElse(null));
      } catch (Exception e) {
        OUTAGE_LOG.error("Exception while sending messages for topic: {}", topic, e);
      } finally {
        encoder.writeArrayEnd();
      }
    }

    encoder.writeMapEnd();
  }

  /**
   * Fetches messages from TMS and encodes each message
   */
  private void fetchAndWriteMessages(Encoder encoder, ByteBuf buffer, ChunkResponder chunkResponder,
                                     String topic, int limit,
                                     @Nullable String fromMessage) throws TopicNotFoundException, IOException {
    try (CloseableIterator<Message> iter = messageFetcher.fetch(NamespaceId.SYSTEM.getNamespace(), topic, limit,
                                                                fromMessage)) {
      while (iter.hasNext()) {
        int size = 0;
        Deque<GenericRecord> monitorMessages = new LinkedList<>();

        // Get the number of messages to be sent in a given chunk
        while (iter.hasNext() && size < CHUNK_SIZE) {
          Message rawMessage = iter.next();
          // Avro requires number of objects to be written first so we will have to buffer messages
          GenericRecord record = createGenericRecord(rawMessage);
          monitorMessages.addLast(record);
          // Avro prefixes string and byte array with its length which is a 32 bit int. So add 8 bytes to size for
          // correct calculation of number bytes on the buffer.
          size += rawMessage.getId().length() + rawMessage.getPayload().length + 8;
        }

        encoder.setItemCount(monitorMessages.size());
        for (GenericRecord monitorMessage : monitorMessages) {
          encoder.startItem();
          writer.write(monitorMessage, encoder);

          if (buffer.readableBytes() >= CHUNK_SIZE) {
            chunkResponder.sendChunk(buffer.copy());
            buffer.clear();
          }
        }
      }
    }
  }

  private GenericRecord createGenericRecord(Message rawMessage) {
    GenericRecord record = new GenericData.Record(MonitorSchemas.V1.MonitorResponse.SCHEMA
                                                    .getValueType().getElementType());
    record.put("messageId", rawMessage.getId());
    record.put("message", rawMessage.getPayload());
    return record;
  }

  private String getTopic(String topicConfig) {
    int idx = topicConfig.lastIndexOf(':');
    return idx < 0 ? cConf.get(topicConfig) : cConf.get(topicConfig.substring(0, idx)) + topicConfig.substring(idx + 1);
  }
}
