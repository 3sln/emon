import 'dart:async';

import 'package:emon/emon.dart';
import 'package:flutter/foundation.dart';

/// Where one pack's download is.
class PackProgress {
  const PackProgress({required this.pack, required this.engine, required this.done, required this.total, required this.state, this.message = ''});

  final String pack;
  final String engine;
  final int done;
  final int total;

  /// `start`, `progress`, `done`, `failed` or `cancelled`.
  final PackEventKind state;
  final String message;

  double get fraction => total <= 0 ? 0 : (done / total).clamp(0, 1).toDouble();
  bool get active => state == PackEventKind.start || state == PackEventKind.progress;
}

/// Follows [Packs.events] for a widget: which packs are downloading, how far, which failed. A [ChangeNotifier], so `ListenableBuilder(listenable: downloads, ...)` rebuilds on each report.
class PackDownloads extends ChangeNotifier {
  PackDownloads(Packs packs) : _packs = packs {
    _sub = packs.events.listen(_onEvent);
  }

  final Packs _packs;
  late final StreamSubscription<PackEvent> _sub;
  final _progress = <String, PackProgress>{};

  /// Every pack that has been reported, newest state each (completed ones stay until [clearFinished]).
  List<PackProgress> get all => List.unmodifiable(_progress.values);

  /// The downloads in progress.
  List<PackProgress> get active => [for (final p in _progress.values) if (p.active) p];

  bool get isDownloading => _progress.values.any((p) => p.active);

  /// Progress of everything in flight together, 0..1 (null when nothing is downloading).
  double? get overall {
    final a = active;
    if (a.isEmpty) return null;
    final total = a.fold<int>(0, (s, p) => s + p.total);
    final done = a.fold<int>(0, (s, p) => s + p.done);
    return total <= 0 ? 0 : (done / total).clamp(0, 1).toDouble();
  }

  /// The most recent failure, or null.
  PackProgress? get lastFailure {
    PackProgress? f;
    for (final p in _progress.values) {
      if (p.state == PackEventKind.failed) f = p;
    }
    return f;
  }

  /// Stop what is downloading.
  void cancel() => _packs.cancel();

  void clearFinished() {
    _progress.removeWhere((_, p) => !p.active);
    notifyListeners();
  }

  void _onEvent(PackEvent e) {
    _progress[e.pack] = PackProgress(pack: e.pack, engine: e.engine, done: e.done, total: e.total, state: e.kind, message: e.message);
    notifyListeners();
  }

  @override
  void dispose() {
    unawaited(_sub.cancel());
    super.dispose();
  }
}
