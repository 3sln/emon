/// Which ML Kit recogniser reads a request's languages, and what the engine's languages are called in emon (ISO 639-3).
library;

const _latin = {
  'eng', 'deu', 'fra', 'spa', 'por', 'ita', 'nld', 'cat', 'ces', 'dan', 'fin', 'hun', 'nor', 'nob', 'pol', 'ron', 'slk', 'slv', 'swe', 'tur', 'vie', 'ind', 'msa', 'hrv', 'lav', 'lit', 'est', 'sqi',
  'isl', 'gle', 'eus', 'glg', 'afr', 'swa', 'fil', 'lat', 'Latn',
};
const _chinese = {'chs', 'cht', 'zho', 'cmn', 'Hans', 'Hant', 'Hani'};
const _japanese = {'jpn', 'Jpan', 'Hira', 'Kana'};
const _korean = {'kor', 'Kore', 'Hang'};
const _devanagari = {'hin', 'mar', 'nep', 'san', 'Deva'};

/// The ML Kit script that reads the languages of a request (emon's codes and ISO 15924 scripts): the first that names a script ML Kit has a recogniser for. An empty list is
/// Latin (no language is assumed, DEVLOG D038, and an unaccented page reads as it does with English alone). Null: none of the languages is one ML Kit reads (Cyrillic, Arabic, Hebrew, Thai, ...).
String? mlkitScriptFor(List<String> languages) {
  if (languages.isEmpty) return 'latin';
  for (final l in languages) {
    if (_latin.contains(l)) return 'latin';
    if (_chinese.contains(l)) return 'chinese';
    if (_japanese.contains(l)) return 'japanese';
    if (_korean.contains(l)) return 'korean';
    if (_devanagari.contains(l)) return 'devanagari';
  }
  return null;
}

const _bcp47 = {
  'en': 'eng', 'de': 'deu', 'fr': 'fra', 'es': 'spa', 'pt': 'por', 'it': 'ita', 'nl': 'nld', 'ca': 'cat', 'cs': 'ces', 'da': 'dan', 'fi': 'fin', 'hu': 'hun', 'no': 'nor', 'nb': 'nob',
  'pl': 'pol', 'ro': 'ron', 'sk': 'slk', 'sl': 'slv', 'sv': 'swe', 'tr': 'tur', 'vi': 'vie', 'id': 'ind', 'ms': 'msa', 'hr': 'hrv', 'lv': 'lav', 'lt': 'lit', 'et': 'est', 'sq': 'sqi',
  'is': 'isl', 'ga': 'gle', 'eu': 'eus', 'gl': 'glg', 'af': 'afr', 'sw': 'swa', 'fil': 'fil', 'la': 'lat', 'ja': 'jpn', 'ko': 'kor', 'hi': 'hin', 'mr': 'mar', 'ne': 'nep', 'sa': 'san',
  'zh': 'chs', 'zh-Hans': 'chs', 'zh-CN': 'chs', 'zh-Hant': 'cht', 'zh-TW': 'cht', 'zh-HK': 'cht',
};

/// The emon (ISO 639-3) code of a BCP-47 tag ML Kit reports, or null (`und`, an unknown language: the engine says it cannot tell).
String? mlkitLanguageOfEmon(String? tag) {
  if (tag == null || tag.isEmpty || tag == 'und') return null;
  return _bcp47[tag] ?? _bcp47[tag.split('-').first];
}
