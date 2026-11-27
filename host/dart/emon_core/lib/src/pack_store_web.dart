/// The web's pack store: there is none. The library has no HTTP or TLS client (DEVLOG D913), and on the web the engines are JS ones that load their
/// own models from where the app put them (`WebTesseract.lang`), so no pack is installed, fetched or asked about here: [Packs.ensure] says
/// `pack-fetch-unavailable`, [Packs.isInstalled] is false, and [Packs.events] is quiet.
library;

import 'dart:async';

import 'packs.dart';

/// See the library comment.
class PackStore implements PackBackend {
  PackStore({this.dir = '', this.mode = PackMode.never, List<String>? sources});

  @override
  final String dir;
  @override
  final PackMode mode;

  final _events = StreamController<PackEvent>.broadcast();

  @override
  Stream<PackEvent> get events => _events.stream;

  @override
  bool get offline => false;

  @override
  PackMode get effectiveMode => PackMode.never;

  @override
  bool isInstalled(PackInfo pack) => false;

  void emit(PackEvent e) {
    if (!_events.isClosed) _events.add(e);
  }

  @override
  void cancelAll() {}

  Future<void> close() => _events.close();

  @override
  Future<String> fetchToStaging(PackInfo pack, {required PackInitiator initiator, CancelToken? cancel}) =>
      throw UnsupportedError('packs are not fetched on the web');

  @override
  void install(PackInfo pack, String stagingDir) {}
}
