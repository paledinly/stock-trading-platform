import 'dart:convert';
import 'dart:io';

import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

const apiBaseUrl = String.fromEnvironment(
  'API_BASE_URL',
  defaultValue: 'http://10.0.2.2:8080',
);

final dioProvider = Provider<Dio>((ref) {
  final dio = Dio(BaseOptions(
    baseUrl: apiBaseUrl,
    connectTimeout: const Duration(seconds: 5),
    receiveTimeout: const Duration(seconds: 15),
    headers: {'Content-Type': 'application/json'},
  ));
  dio.interceptors.add(InterceptorsWrapper(onRequest: (options, handler) {
    options.headers['X-Correlation-ID'] =
        'mobile-${DateTime.now().microsecondsSinceEpoch}';
    handler.next(options);
  }));
  return dio;
});

class MarketEvent {
  const MarketEvent(this.id, this.type, this.payload);
  final String id;
  final String type;
  final Map<String, dynamic> payload;
}

final marketEventProvider = StreamProvider<MarketEvent>((ref) async* {
  var lastEventId = '';
  while (true) {
    final client = HttpClient();
    try {
      final request = await client.getUrl(Uri.parse('$apiBaseUrl/api/v1/stream'));
      request.headers.set(HttpHeaders.acceptHeader, 'text/event-stream');
      if (lastEventId.isNotEmpty) request.headers.set('Last-Event-ID', lastEventId);
      final response = await request.close();
      String id = '', type = '';
      await for (final line in response.transform(utf8.decoder).transform(const LineSplitter())) {
        if (line.startsWith('id:')) id = line.substring(3).trim();
        if (line.startsWith('event:')) type = line.substring(6).trim();
        if (line.startsWith('data:')) {
          final json = jsonDecode(line.substring(5).trim()) as Map<String, dynamic>;
          lastEventId = id;
          yield MarketEvent(id, type, (json['payload'] as Map?)?.cast<String, dynamic>() ?? {});
        }
      }
    } catch (_) {
      await Future<void>.delayed(const Duration(seconds: 2));
    } finally {
      client.close(force: true);
    }
  }
});
