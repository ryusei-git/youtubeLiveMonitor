const {test} = require('node:test');
const assert = require('node:assert/strict');
const {loadStatic} = require('./static-script.cjs');
const {embeddedVideoUrl} = loadStatic(['youtubeEmbedUrl', 'embeddedVideoUrl']);
const embed = url => embeddedVideoUrl(url, {hostname: 'localhost', origin: 'http://localhost'});
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
