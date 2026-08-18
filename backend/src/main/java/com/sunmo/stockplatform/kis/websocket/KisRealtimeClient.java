package com.sunmo.stockplatform.kis.websocket;
import com.sunmo.stockplatform.market.application.*;
import com.sunmo.stockplatform.market.config.RealtimeMarketProperties;
import org.slf4j.*;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import java.net.http.*;
import java.util.concurrent.*;

@Component
@ConditionalOnProperty(prefix="market.realtime",name="enabled",havingValue="true")
public class KisRealtimeClient implements ApplicationRunner,WebSocket.Listener {
 private static final Logger log=LoggerFactory.getLogger(KisRealtimeClient.class);private final KisApprovalClient approval;private final KisRealtimeTickParser parser;private final MarketDataService market;private final RealtimeSubscriptionRegistry subscriptions;private final RealtimeMarketProperties properties;private final ScheduledExecutorService reconnect=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"kis-ws-reconnect");t.setDaemon(true);return t;});private volatile WebSocket socket;private volatile String approvalKey;private final StringBuilder fragments=new StringBuilder();
 public KisRealtimeClient(KisApprovalClient approval,KisRealtimeTickParser parser,MarketDataService market,RealtimeSubscriptionRegistry subscriptions,RealtimeMarketProperties properties){this.approval=approval;this.parser=parser;this.market=market;this.subscriptions=subscriptions;this.properties=properties;}
 @Override public void run(ApplicationArguments args){subscriptions.onAdded(this::subscribe);connect();}
 private void connect(){try{approvalKey=approval.issue();HttpClient.newHttpClient().newWebSocketBuilder().buildAsync(properties.websocketUrl(),this).thenAccept(ws->{socket=ws;subscriptions.all().forEach(this::subscribe);}).exceptionally(error->{scheduleReconnect(error);return null;});}catch(RuntimeException error){scheduleReconnect(error);}}
 private void scheduleReconnect(Throwable error){log.warn("KIS websocket disconnected; retrying in 5 seconds: {}",error.getMessage());reconnect.schedule(this::connect,5,TimeUnit.SECONDS);}
 private void subscribe(String code){WebSocket ws=socket;if(ws==null||approvalKey==null)return;String message="{\"header\":{\"approval_key\":\""+approvalKey+"\",\"custtype\":\"P\",\"tr_type\":\"1\",\"content-type\":\"utf-8\"},\"body\":{\"input\":{\"tr_id\":\"H0STCNT0\",\"tr_key\":\""+code+"\"}}}";ws.sendText(message,true);}
 @Override public void onOpen(WebSocket webSocket){webSocket.request(1);}
 @Override public CompletionStage<?> onText(WebSocket webSocket,CharSequence data,boolean last){fragments.append(data);if(last){String message=fragments.toString();fragments.setLength(0);handle(webSocket,message);}webSocket.request(1);return null;}
 private void handle(WebSocket webSocket,String message){try{if(message.contains("PINGPONG")){webSocket.sendText(message,true);return;}if(!message.startsWith("0|H0STCNT0|"))return;String[] parts=message.split("\\|",4);if(parts.length==4)market.onTick(parser.parse(parts[3]));}catch(RuntimeException error){log.warn("Ignored invalid KIS realtime message: {}",error.getMessage());}}
 @Override public CompletionStage<?> onClose(WebSocket webSocket,int status,String reason){socket=null;scheduleReconnect(new IllegalStateException("close "+status));return null;}
 @Override public void onError(WebSocket webSocket,Throwable error){socket=null;scheduleReconnect(error);}
}
