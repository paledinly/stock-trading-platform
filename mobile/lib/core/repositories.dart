// 백엔드 REST API 호출을 한곳에 모은 저장소(Repository)와 화면용 provider를 정의해요.
// 화면은 이 파일의 provider만 보고, 실제 HTTP 호출은 StockRepository가 Dio로 처리해요.
// features/screens.dart의 화면들이 사용해요.
import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import 'api_client.dart';
import 'models.dart';

/// 공용 Dio를 받아 StockRepository를 만들어 주는 provider예요.
final repositoryProvider = Provider((ref) => StockRepository(ref.watch(dioProvider)));

/// 서버 API 창구 역할을 하는 클래스예요. 은행 창구 직원처럼 화면의 요청을 받아 서버에 대신 전달해요.
/// 각 메서드는 비동기(async, 결과를 나중에 받는 방식)로 Future를 돌려줘요.
class StockRepository {
  StockRepository(this._dio);
  final Dio _dio;
  /// 종목 검색이에요. 받는 것: 검색어. 돌려주는 것: 최대 20개 종목 목록.
  Future<List<Stock>> search(String query) async => ((await _dio.get<List<dynamic>>('/api/v1/stocks/search', queryParameters: {'q': query, 'limit': 20})).data ?? []).map((e) => Stock.fromJson((e as Map).cast())).toList();
  /// 한 종목의 현재 시세를 가져와요. 받는 것: 종목 코드. 돌려주는 것: Quote.
  Future<Quote> quote(String code) async => Quote.fromJson(((await _dio.get<Map<String, dynamic>>('/api/v1/stocks/$code/quote')).data)!);
  /// 관심종목 그룹 전체를 가져와요. 돌려주는 것: 그룹 목록(groups가 없으면 빈 목록).
  Future<List<WatchlistGroup>> watchlists() async { final data=(await _dio.get<Map<String,dynamic>>('/api/v1/watchlists')).data!;return ((data['groups'] as List?)??[]).map((e)=>WatchlistGroup.fromJson((e as Map).cast())).toList(); }
  /// 새 관심종목 그룹을 만들어요. 받는 것: 그룹 이름.
  Future<void> createGroup(String name) => _dio.post('/api/v1/watchlist-groups', data: {'name': name});
  /// 그룹에 종목을 추가해요. 받는 것: 그룹 id, 종목 코드.
  Future<void> addWatchlist(int groupId,String code) => _dio.post('/api/v1/watchlists',data:{'groupId':groupId,'stockCode':code});
  /// 관심종목 항목을 지워요. 받는 것: 항목 id.
  Future<void> removeWatchlist(int id) => _dio.delete('/api/v1/watchlists/$id');
  /// Scanner 탐지 목록을 가져와요. 받는 것: 탐지 종류(null이면 전체). 돌려주는 것: 최대 50건.
  Future<List<Detection>> detections(String? type) async => ((await _dio.get<List<dynamic>>('/api/v1/scanner-detections',queryParameters:{if(type!=null)'type':type,'limit':50})).data??[]).map((e)=>Detection.fromJson((e as Map).cast())).toList();
  /// 최근 수동 매매 기록 최대 100건을 가져와요.
  Future<List<TradeRecord>> trades() async => ((await _dio.get<List<dynamic>>('/api/v1/trades',queryParameters:{'limit':100})).data??[]).map((e)=>TradeRecord.fromJson((e as Map).cast())).toList();
  /// 수동 매매 기록을 저장해요(실제 주문 아님). 받는 것: 종목 코드, BUY/SELL, 가격, 수량.
  /// 거래 시각은 저장 버튼을 누른 현재 시각(UTC)으로 기록돼요.
  /// Idempotency-Key(같은 요청이 두 번 저장되지 않게 하는 꼬리표)를 시각으로 만들어 붙여요.
  Future<void> createTrade({required String code,required String type,required num price,required int quantity}) => _dio.post('/api/v1/trades',data:{'stockCode':code,'tradeType':type,'tradedAt':DateTime.now().toUtc().toIso8601String(),'price':price,'quantity':quantity},options:Options(headers:{'Idempotency-Key':'mobile-${DateTime.now().microsecondsSinceEpoch}'}));
}

// 아래는 화면이 구독하는 FutureProvider(한 번 불러온 결과를 캐시해 두는 provider)들이에요.
// family는 인자(탐지 종류, 종목 코드, 검색어)마다 따로 캐시를 둬요.
final watchlistsProvider = FutureProvider((ref) => ref.watch(repositoryProvider).watchlists());
final tradesProvider = FutureProvider((ref) => ref.watch(repositoryProvider).trades());
final detectionProvider = FutureProvider.family<List<Detection>, String?>((ref,type) => ref.watch(repositoryProvider).detections(type));
final quoteProvider = FutureProvider.family<Quote,String>((ref,code)=>ref.watch(repositoryProvider).quote(code));
// 검색어가 비어 있으면 서버를 부르지 않고 빈 목록을 돌려줘요.
final searchProvider = FutureProvider.family<List<Stock>,String>((ref,query)=>query.trim().isEmpty?Future.value([]):ref.watch(repositoryProvider).search(query));
