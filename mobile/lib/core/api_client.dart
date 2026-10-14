// 백엔드 서버와 통신하는 기본 도구를 모아 둔 파일이에요.
// Dio(HTTP 요청을 보내는 라이브러리) 설정과 SSE(서버가 이벤트를 계속 밀어 보내는 방식) 실시간 스트림 구독을 담당해요.
// repositories.dart와 화면들이 이 provider들을 사용해요.
import 'dart:convert';
import 'dart:io';

import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

/// 백엔드 주소예요. 빌드할 때 --dart-define=API_BASE_URL=... 로 바꿀 수 있어요.
/// 기본값 10.0.2.2는 안드로이드 에뮬레이터에서 PC(호스트)의 localhost를 가리키는 특수 주소예요.
const apiBaseUrl = String.fromEnvironment(
  'API_BASE_URL',
  defaultValue: 'http://10.0.2.2:8080',
);

/// 공용 Dio 클라이언트를 만드는 provider예요.
/// 받는 것: 없음. 돌려주는 것: 기본 주소·타임아웃·JSON 헤더가 설정된 Dio.
final dioProvider = Provider<Dio>((ref) {
  final dio = Dio(BaseOptions(
    baseUrl: apiBaseUrl,
    connectTimeout: const Duration(seconds: 5),
    receiveTimeout: const Duration(seconds: 15),
    headers: {'Content-Type': 'application/json'},
  ));
  // 요청마다 추적용 X-Correlation-ID 헤더를 붙여 서버 로그에서 요청을 찾기 쉽게 해요.
  dio.interceptors.add(InterceptorsWrapper(onRequest: (options, handler) {
    options.headers['X-Correlation-ID'] =
        'mobile-${DateTime.now().microsecondsSinceEpoch}';
    handler.next(options);
  }));
  return dio;
});

/// SSE로 받은 실시간 이벤트 한 건이에요. 우편함에 도착한 편지 한 통처럼 번호(id)·종류(type)·내용(payload)을 담아요.
class MarketEvent {
  const MarketEvent(this.id, this.type, this.payload);
  final String id;
  final String type;
  final Map<String, dynamic> payload;
}

/// 서버의 /api/v1/stream SSE에 붙어 이벤트를 계속 흘려보내는 StreamProvider(시간에 따라 값이 계속 바뀌는 provider)예요.
/// 받는 것: 없음. 돌려주는 것: MarketEvent 스트림.
/// 연결이 끊기거나 오류가 나면 2초 쉬었다가 다시 연결해요.
final marketEventProvider = StreamProvider<MarketEvent>((ref) async* {
  // 마지막으로 받은 이벤트 번호예요. 재연결 때 Last-Event-ID로 보내 놓친 이벤트를 이어 받으려는 것으로 보여요.
  var lastEventId = '';
  while (true) {
    // 웹의 EventSource 대신 dart:io HttpClient로 SSE 텍스트를 한 줄씩 직접 읽어요.
    final client = HttpClient();
    try {
      final request = await client.getUrl(Uri.parse('$apiBaseUrl/api/v1/stream'));
      request.headers.set(HttpHeaders.acceptHeader, 'text/event-stream');
      if (lastEventId.isNotEmpty) request.headers.set('Last-Event-ID', lastEventId);
      final response = await request.close();
      // SSE는 'id:', 'event:', 'data:' 줄로 한 이벤트를 보내요. data 줄이 오면 앞서 받은 id·event와 묶어 내보내요.
      // ⚠️ 확인 필요: 빈 줄(이벤트 경계)에서 id·type을 초기화하지 않아, 다음 이벤트에 event: 줄이 없으면 이전 type이 남는 것으로 보여요.
      String id = '', type = '';
      await for (final line in response.transform(utf8.decoder).transform(const LineSplitter())) {
        if (line.startsWith('id:')) id = line.substring(3).trim();
        if (line.startsWith('event:')) type = line.substring(6).trim();
        if (line.startsWith('data:')) {
          // ⚠️ 확인 필요: data가 JSON 객체가 아니면 예외가 나서 연결 전체가 끊기고 2초 뒤 재연결돼요. 한 줄 오류로 스트림이 재시작되는 셈이에요.
          final json = jsonDecode(line.substring(5).trim()) as Map<String, dynamic>;
          lastEventId = id;
          yield MarketEvent(id, type, (json['payload'] as Map?)?.cast<String, dynamic>() ?? {});
        }
      }
    // 네트워크 오류 등은 조용히 넘기고 잠시 기다린 뒤 while 루프에서 다시 연결해요.
    } catch (_) {
      await Future<void>.delayed(const Duration(seconds: 2));
    } finally {
      // 매 연결마다 만든 HttpClient를 반드시 닫아 소켓이 남지 않게 해요.
      client.close(force: true);
    }
  }
});
