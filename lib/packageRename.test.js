import assert from "node:assert/strict";
import test from "node:test";
import { renameManifestPackage, replacePrefix, withSuffix } from "./packageRename.js";

test("adds .patched a single time", () => {
  assert.equal(withSuffix("com.example.game"), "com.example.game.patched");
  assert.equal(withSuffix("com.example.game.patched"), "com.example.game.patched");
});

test("keeps class names and rewrites the install identity", () => {
  const xml = `
    <manifest package="com.example.game" android:sharedUserId="com.example.game">
      <uses-permission android:name="com.example.game.permission.C2D"/>
      <permission android:name="com.example.game.permission.C2D"/>
      <permission android:name="com.example.extra.permission.KEEP"/>
      <application android:name=".App">
        <activity android:name=".Main" android:permission="com.example.game.permission.C2D">
          <intent-filter>
            <action android:name="android.intent.action.MAIN"/>
          </intent-filter>
        </activity>
        <provider android:name=".Box" android:authorities="com.example.game.files;com.example.extra.box"/>
      </application>
    </manifest>
  `;
  const renamed = renameManifestPackage(xml);
  assert.equal(renamed.renamed, true);
  assert.equal(renamed.packageName, "com.example.game.patched");
  assert.match(renamed.xml, /package="com\.example\.game\.patched"/);
  assert.match(renamed.xml, /android:name="com\.example\.game\.App"/);
  assert.match(renamed.xml, /android:name="com\.example\.game\.Main"/);
  assert.match(renamed.xml, /android:name="android\.intent\.action\.MAIN"/);
  assert.match(renamed.xml, /android:authorities="com\.example\.game\.patched\.files;com\.example\.extra\.box\.patched"/);
  assert.match(renamed.xml, /android:name="com\.example\.game\.patched\.permission\.C2D"/);
  assert.doesNotMatch(renamed.xml, /sharedUserId/);
  assert.equal(replacePrefix("com.other.Main", "com.example.game", "com.example.game.patched"), "com.other.Main");
  assert.match(renamed.xml, /android:name="com\.example\.extra\.permission\.KEEP\.patched"/);
  assert.match(renamed.xml, /android:authorities="com\.example\.game\.patched\.files;com\.example\.extra\.box\.patched"/);
});
