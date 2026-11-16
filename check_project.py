from pathlib import Path
from xml.etree import ElementTree as ET
import re
root = Path(__file__).resolve().parent
needed = [
    'settings.gradle','build.gradle','app/build.gradle',
    'app/src/main/AndroidManifest.xml',
    'app/src/main/java/ru/ulyanovsk/bookshelf/MainActivity.java',
    'app/src/main/assets/index.html',
    'app/src/main/res/drawable/ic_book.xml',
    'app/src/main/res/xml/network_security_config.xml',
    '.github/workflows/build-apk.yml'
]
for f in needed:
    p=root/f
    assert p.exists() and p.stat().st_size > 0, f'Missing: {f}'
for f in root.rglob('*.xml'):
    ET.parse(f)
html=(root/'app/src/main/assets/index.html').read_text(encoding='utf-8')
java=(root/'app/src/main/java/ru/ulyanovsk/bookshelf/MainActivity.java').read_text(encoding='utf-8')
assert 'window.__bookshelfReply' in html and 'function home()' in html
assert 'class MainActivity extends Activity' in java
assert 'JavascriptInterface' in java
assert 'setJavaScriptEnabled(true)' in java
assert 'EXTERNAL_CONTENT_URI' in java
assert 'file:///android_asset/index.html' in java
assert 'applicationId' in (root/'app/build.gradle').read_text(encoding='utf-8')
print('Проверка структуры Android-проекта пройдена. Это не компиляция APK.')

# JavaScript syntax check (Node.js is available on GitHub's Ubuntu runner).
import subprocess
import tempfile
start = html.index('<script>')+len('<script>')
end = html.index('</script>',start)
with tempfile.NamedTemporaryFile(mode='w',suffix='.js',encoding='utf-8') as f:
    f.write(html[start:end]); f.flush()
    subprocess.run(['node','--check',f.name],check=True)
assert 'void selfTest(' in java and 'void diagnose(' in java
assert 'copyPickedFile(' in java
print('Проверка JavaScript и функций диагностики пройдена.')
