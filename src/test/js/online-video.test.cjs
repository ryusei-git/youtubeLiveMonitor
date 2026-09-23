const {test} = require('node:test');
const assert = require('node:assert/strict');
const vm = require('node:vm');
const fs = require('node:fs');
const text = fs.readFileSync('src/main/resources/static/js/common.js','utf8');
const start = text.indexOf('function embeddedVideoUrl(');
const context = vm.createContext({URL, URLSearchParams});
vm.runInContext(text.slice(start, text.indexOf('\n/**', start)), context);
const embed = url => context.embeddedVideoUrl(url, 'localhost');
test('YouTubeの動画IDを公式埋め込みURLに変換する', () => {
 assert.match(embed('https://www.youtube.com/watch?v=abcdefghijk'), /^https:\/\/www.youtube-nocookie.com\/embed\/abcdefghijk\?/);
});
test('Twitchの配信ページとVODを区別し現在のホストをparentに使う', () => {
 assert.equal(new URL(embed('https://www.twitch.tv/videos/1234')).searchParams.get('video'), 'v1234');
 const live=new URL(embed('https://www.twitch.tv/example'));
 assert.equal(live.searchParams.get('channel'),'example'); assert.equal(live.searchParams.get('parent'),'localhost');
});
test('外部URL・偽ドメイン・不正な動画IDを埋め込まない', () => {
 for(const url of ['javascript:alert(1)', 'https://youtube.com.evil.example/watch?v=abcdefghijk','https://www.youtube.com/watch?v=bad','https://evil.example/']) assert.equal(embed(url),null);
});
