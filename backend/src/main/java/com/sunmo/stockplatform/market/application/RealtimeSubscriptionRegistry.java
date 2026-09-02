package com.sunmo.stockplatform.market.application;
import org.springframework.stereotype.Component;
import com.sunmo.stockplatform.market.config.RealtimeMarketProperties;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BiConsumer;
@Component
public class RealtimeSubscriptionRegistry {
 public enum Source { QUOTE, WATCHLIST, MANUAL }
 private final int limit;private final Map<String,Set<Source>> entries=new ConcurrentHashMap<>();private volatile BiConsumer<String,Boolean> listener=(code,subscribe)->{};
 public RealtimeSubscriptionRegistry(RealtimeMarketProperties properties){this.limit=properties.subscriptionLimit();}
 public boolean add(String code){return add(code,Source.QUOTE);}
 public synchronized boolean add(String code,Source source){Set<Source> existing=entries.get(code);if(existing!=null)return existing.add(source);if(entries.size()>=limit)throw new IllegalStateException("KIS realtime subscription limit reached: "+limit);entries.put(code,ConcurrentHashMap.newKeySet());entries.get(code).add(source);listener.accept(code,true);return true;}
 public synchronized boolean remove(String code,Source source){Set<Source> sources=entries.get(code);if(sources==null||!sources.remove(source))return false;if(sources.isEmpty()){entries.remove(code);listener.accept(code,false);}return true;}
 public Set<String> all(){return Set.copyOf(entries.keySet());}public Map<String,Set<Source>> entries(){Map<String,Set<Source>> copy=new TreeMap<>();entries.forEach((code,sources)->copy.put(code,Set.copyOf(sources)));return Map.copyOf(copy);}public int limit(){return limit;}public int remaining(){return Math.max(0,limit-entries.size());}
 public void onChanged(BiConsumer<String,Boolean> listener){this.listener=listener;}
}
