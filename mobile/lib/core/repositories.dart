import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import 'api_client.dart';
import 'models.dart';

final repositoryProvider = Provider((ref) => StockRepository(ref.watch(dioProvider)));

class StockRepository {
  StockRepository(this._dio);
  final Dio _dio;
  Future<List<Stock>> search(String query) async => ((await _dio.get<List<dynamic>>('/api/v1/stocks/search', queryParameters: {'q': query, 'limit': 20})).data ?? []).map((e) => Stock.fromJson((e as Map).cast())).toList();
  Future<Quote> quote(String code) async => Quote.fromJson(((await _dio.get<Map<String, dynamic>>('/api/v1/stocks/$code/quote')).data)!);
  Future<List<WatchlistGroup>> watchlists() async { final data=(await _dio.get<Map<String,dynamic>>('/api/v1/watchlists')).data!;return ((data['groups'] as List?)??[]).map((e)=>WatchlistGroup.fromJson((e as Map).cast())).toList(); }
  Future<void> createGroup(String name) => _dio.post('/api/v1/watchlist-groups', data: {'name': name});
  Future<void> addWatchlist(int groupId,String code) => _dio.post('/api/v1/watchlists',data:{'groupId':groupId,'stockCode':code});
  Future<void> removeWatchlist(int id) => _dio.delete('/api/v1/watchlists/$id');
  Future<List<Detection>> detections(String? type) async => ((await _dio.get<List<dynamic>>('/api/v1/scanner-detections',queryParameters:{if(type!=null)'type':type,'limit':50})).data??[]).map((e)=>Detection.fromJson((e as Map).cast())).toList();
  Future<List<TradeRecord>> trades() async => ((await _dio.get<List<dynamic>>('/api/v1/trades',queryParameters:{'limit':100})).data??[]).map((e)=>TradeRecord.fromJson((e as Map).cast())).toList();
  Future<void> createTrade({required String code,required String type,required num price,required int quantity}) => _dio.post('/api/v1/trades',data:{'stockCode':code,'tradeType':type,'tradedAt':DateTime.now().toUtc().toIso8601String(),'price':price,'quantity':quantity},options:Options(headers:{'Idempotency-Key':'mobile-${DateTime.now().microsecondsSinceEpoch}'}));
}

final watchlistsProvider = FutureProvider((ref) => ref.watch(repositoryProvider).watchlists());
final tradesProvider = FutureProvider((ref) => ref.watch(repositoryProvider).trades());
final detectionProvider = FutureProvider.family<List<Detection>, String?>((ref,type) => ref.watch(repositoryProvider).detections(type));
final quoteProvider = FutureProvider.family<Quote,String>((ref,code)=>ref.watch(repositoryProvider).quote(code));
final searchProvider = FutureProvider.family<List<Stock>,String>((ref,query)=>query.trim().isEmpty?Future.value([]):ref.watch(repositoryProvider).search(query));
