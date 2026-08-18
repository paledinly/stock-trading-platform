package com.sunmo.stockplatform.market.application;
import org.springframework.stereotype.Component;
import java.util.Set;
import java.util.concurrent.*;
import java.util.function.Consumer;
@Component
public class RealtimeSubscriptionRegistry {private final Set<String> codes=ConcurrentHashMap.newKeySet();private volatile Consumer<String> listener=ignored->{};public boolean add(String code){boolean added=codes.add(code);if(added)listener.accept(code);return added;}public Set<String> all(){return Set.copyOf(codes);}public void onAdded(Consumer<String> listener){this.listener=listener;}}
