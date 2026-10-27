import fs from "node:fs";
import path from "node:path";
import { withSuffix } from "./packageRename.js";

export function stashSidecars(extracted, destRoot, packageSuffix = "") {
  fs.rmSync(destRoot, { recursive: true, force: true });
  if (!fs.existsSync(extracted)) return;
  let oldName = "";
  const manifestPath = path.join(extracted, "manifest.json");
  if (fs.existsSync(manifestPath)) {
    try {
      oldName = JSON.parse(fs.readFileSync(manifestPath, "utf8")).package_name || "";
    } catch {
      oldName = "";
    }
  }
  const newName = oldName && packageSuffix ? withSuffix(oldName, packageSuffix) : oldName;
  let copied = 0;
  function walk(dir) {
    for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
      const full = path.join(dir, entry.name);
      if (entry.isDirectory()) {
        walk(full);
        continue;
      }
      const relative = path.relative(extracted, full).replace(/\\/g, "/");
      if (relative === "manifest.json" || relative.endsWith(".apk")) continue;
      const renamed = relative
        .split("/")
        .map((segment) => (oldName && newName && segment === oldName ? newName : segment))
        .join("/");
      if (renamed.split("/").includes("..")) continue;
      const target = path.join(destRoot, renamed);
      fs.mkdirSync(path.dirname(target), { recursive: true });
      fs.copyFileSync(full, target);
      copied += 1;
    }
  }
  walk(extracted);
  if (copied === 0) fs.rmSync(destRoot, { recursive: true, force: true });
}

export function sidecarFiles(root) {
  if (!fs.existsSync(root)) return [];
  const found = [];
  function walk(dir) {
    for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
      const full = path.join(dir, entry.name);
      if (entry.isDirectory()) walk(full);
      else {
        const relative = path.relative(root, full).replace(/\\/g, "/");
        if (relative.startsWith("Android/") && !relative.split("/").includes("..")) found.push({ file: full, relative });
      }
    }
  }
  walk(root);
  return found;
}
