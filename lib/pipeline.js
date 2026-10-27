import { execFile } from "node:child_process";
import fs from "node:fs";
import path from "node:path";
import { addKeyboardDex, addNavigationDex, writeCarLibraryValues } from "./navigationDex.js";
import { navigationDescriptorName, patchManifest } from "./patch.js";
import { assertTools, ensureSigningKey, paths, SIGNING_ALIAS } from "./tools.js";

export const STEPS = [
  { id: "decode", label: "Prepare" },
  { id: "patch", label: "Update" },
  { id: "build", label: "Build" },
  { id: "sign", label: "Finish" },
];

export function phase(onProgress, start, end, label) {
  let last = -1;
  return (ratio) => {
    const percent = Math.max(0, Math.min(100, Math.round(start + (end - start) * Math.max(0, Math.min(1, Number(ratio) || 0)))));
    if (percent <= last) return;
    last = percent;
    onProgress(percent, label);
  };
}

export function watchFile(file, expected, report, cap = 0.9) {
  const timer = setInterval(() => {
    try {
      const size = fs.existsSync(file) ? fs.statSync(file).size : 0;
      const ratio = Math.min(cap, size / Math.max(1, expected));
      if (ratio > 0) report(ratio);
    } catch {
      // The file is still being created.
    }
  }, 400);
  return () => clearInterval(timer);
}

function run(command, args) {
  return new Promise((resolve, reject) => {
    execFile(
      command,
      args,
      { maxBuffer: 64 * 1024 * 1024, timeout: 15 * 60 * 1000, windowsHide: true },
      (error, stdout, stderr) => {
        const output = `${stdout || ""}${stderr || ""}`.trim();
        if (error) {
          error.toolOutput = output;
          reject(error);
          return;
        }
        resolve(output);
      },
    );
  });
}

async function logged(log, command, args) {
  const printable = args.map((arg) => (/\s/.test(arg) ? `"${arg}"` : arg)).join(" ");
  log.push(`$ ${path.basename(command)} ${printable}`);
  const output = await run(command, args);
  if (output) log.push(output);
  return output;
}

function rm(target) {
  fs.rmSync(target, { recursive: true, force: true });
}

function directorySize(dir) {
  if (!fs.existsSync(dir)) return 0;
  let total = 0;
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) total += directorySize(full);
    else total += fs.statSync(full).size;
  }
  return total;
}

async function decode(java, input, output, log, mode, onRatio = () => {}) {
  rm(output);
  const total = Math.max(1, fs.statSync(input).size);
  onRatio(0);
  const timer = setInterval(() => {
    try {
      const written = directorySize(output);
      onRatio(written / total);
    } catch {
      // The decode folder is still being created.
    }
  }, 400);
  const args = ["-Xmx2048m", "-Duser.language=en", "-jar", paths.apktool, "d", "-f", "-s"];
  if (mode === "broken") args.push("--keep-broken-res");
  args.push("-p", paths.framework, "-o", output, input);
  try {
    await logged(log, java, args);
  } finally {
    clearInterval(timer);
  }
}

function unknownManifestAttributes(output) {
  return [...new Set([...String(output || "").matchAll(/attribute android:([A-Za-z0-9_.]+) not found/g)].map((match) => match[1]))];
}

function stripUnknownAttributes(decoded, names) {
  const manifestPath = path.join(decoded, "AndroidManifest.xml");
  let xml = fs.readFileSync(manifestPath, "utf8");
  for (const name of names) {
    const escaped = name.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
    xml = xml.replace(new RegExp(`\\s+android:${escaped}\\s*=\\s*("[^"]*"|'[^']*')`, "g"), "");
  }
  fs.writeFileSync(manifestPath, xml, "utf8");
}

async function build(java, decoded, output, log) {
  let lastError = null;
  for (let attempt = 0; attempt < 4; attempt += 1) {
    if (fs.existsSync(output)) fs.rmSync(output, { force: true });
    try {
      await logged(log, java, [
        "-Xmx2048m",
        "-Duser.language=en",
        "-jar",
        paths.apktool,
        "b",
        "-f",
        "-p",
        paths.framework,
        "-o",
        output,
        decoded,
      ]);
      if (!fs.existsSync(output)) throw new Error("apktool finished without writing an APK.");
      return;
    } catch (error) {
      lastError = error;
      const names = unknownManifestAttributes(error.toolOutput);
      if (names.length === 0) break;
      log.push(`Removed manifest attributes this build tools version does not know: ${names.join(", ")}.`);
      stripUnknownAttributes(decoded, names);
    }
  }
  throw lastError;
}

function stripInternalBuildFiles(decoded) {
  const blocked = new Set(["stamp-cert-sha256"]);
  function walk(dir) {
    if (!fs.existsSync(dir)) return;
    for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
      const full = path.join(dir, entry.name);
      if (entry.name === "SEC-INF" || blocked.has(entry.name)) {
        fs.rmSync(full, { recursive: true, force: true });
        continue;
      }
      if (entry.isDirectory()) walk(full);
    }
  }
  walk(decoded);
}

function writeTouchBridge(decoded) {
  const values = path.join(decoded, "res", "values", "automaker_touch_bridge.xml");
  const file = path.join(decoded, "res", "xml", "automaker_touch_bridge.xml");
  fs.mkdirSync(path.dirname(values), { recursive: true });
  fs.mkdirSync(path.dirname(file), { recursive: true });
  fs.writeFileSync(
    values,
    "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<resources>\n    <string name=\"automaker_touch_bridge_description\">Forwards touches from the car screen.</string>\n</resources>\n",
    "utf8",
  );
  fs.writeFileSync(
    file,
    "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<accessibility-service xmlns:android=\"http://schemas.android.com/apk/res/android\"\n    android:description=\"@string/automaker_touch_bridge_description\"\n    android:accessibilityEventTypes=\"typeAllMask\"\n    android:accessibilityFeedbackType=\"feedbackGeneric\"\n    android:accessibilityFlags=\"flagSendMotionEvents|flagRetrieveInteractiveWindows|flagReportViewIds|flagIncludeNotImportantViews\"\n    android:canRetrieveWindowContent=\"true\"\n    android:canPerformGestures=\"true\"\n    android:canRequestTouchExplorationMode=\"true\"\n    android:notificationTimeout=\"0\"/>\n",
    "utf8",
  );
}

function writeDrivingDescriptor(decoded, manifestXml) {
  const name = navigationDescriptorName(manifestXml);
  const file = path.join(decoded, "res", "xml", `${name}.xml`);
  fs.mkdirSync(path.dirname(file), { recursive: true });
  fs.writeFileSync(
    file,
    "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<automotiveApp>\n    <uses name=\"template\"/>\n</automotiveApp>\n",
    "utf8",
  );
}

function applyManifestPatch(decoded, kind, packageSuffix, gamepad) {
  const manifestPath = path.join(decoded, "AndroidManifest.xml");
  if (!fs.existsSync(manifestPath)) {
    throw new Error("The decoded APK has no AndroidManifest.xml.");
  }
  const result = patchManifest(fs.readFileSync(manifestPath, "utf8"), kind, packageSuffix, gamepad);
  fs.writeFileSync(manifestPath, result.xml, "utf8");
  if (kind === "navigation") {
    writeCarLibraryValues(decoded);
    writeDrivingDescriptor(decoded, result.xml);
    writeTouchBridge(decoded);
  }
  return result;
}

export async function alignAndSign(java, inputPath, outputPath, packageName, log) {
  const { keystore, passwordFile } = ensureSigningKey(packageName);
  const aligned = path.join(path.dirname(outputPath), `.aligning-${path.basename(outputPath)}`);
  try {
    await logged(log, paths.zipalign, ["-P", "16", "-f", "4", inputPath, aligned]);
  } catch (error) {
    log.push("16 KB alignment failed. Aligning to 4 bytes.");
    if (error.toolOutput) log.push(error.toolOutput);
    await logged(log, paths.zipalign, ["-p", "-f", "4", inputPath, aligned]);
  }
  await logged(log, java, [
    "-jar",
    paths.apksigner,
    "sign",
    "--ks",
    keystore,
    "--ks-key-alias",
    SIGNING_ALIAS,
    "--ks-pass",
    `file:${passwordFile}`,
    "--out",
    outputPath,
    aligned,
  ]);
  await logged(log, java, ["-jar", paths.apksigner, "verify", "--verbose", outputPath]);
  fs.rmSync(aligned, { force: true });
}

export async function runPipeline({ inputPath, outputPath, onStep = () => {}, log = [], kind = "game", packageSuffix = "", gamepad = false, onProgress = () => {} }) {
  const { java } = assertTools();
  const directory = path.dirname(outputPath);
  const decoded = path.join(directory, "decoded");
  const unsigned = path.join(directory, "unsigned.apk");

  const inputSize = Math.max(1, fs.statSync(inputPath).size);
  const preparing = phase(onProgress, 0, 45, "Preparing the package");
  onStep("decode");
  log.push("Preparing the package.");
  preparing(0);
  try {
    await decode(java, inputPath, decoded, log, "full", (ratio) => preparing(Math.min(0.92, ratio)));
  } catch (error) {
    log.push("Decode failed. Trying again and keeping broken resources.");
    if (error.toolOutput) log.push(error.toolOutput);
    await decode(java, inputPath, decoded, log, "broken", (ratio) => preparing(Math.min(0.92, ratio)));
  }
  preparing(1);

  const updating = phase(onProgress, 45, 52, "Updating the manifest");
  onStep("patch");
  log.push("Updating the manifest.");
  updating(0);
  const patched = applyManifestPatch(decoded, kind === "navigation" ? "navigation" : "game", packageSuffix, gamepad);
  stripInternalBuildFiles(decoded);
  for (const change of patched.changes) log.push(change);
  updating(1);

  const building = phase(onProgress, 52, 90, "Building the APK");
  onStep("build");
  log.push("Building the APK.");
  building(0);
  const stopBuild = watchFile(unsigned, inputSize, (ratio) => building(Math.min(0.9, ratio)));
  try {
    await build(java, decoded, unsigned, log);
  } finally {
    stopBuild();
  }
  if (kind === "navigation") {
    log.push("Adding the navigation screen.");
    addNavigationDex(unsigned);
  } else {
    addKeyboardDex(unsigned);
  }
  building(1);

  const signing = phase(onProgress, 90, 100, "Signing");
  onStep("sign");
  log.push("Signing the APK.");
  signing(0);
  await alignAndSign(java, unsigned, outputPath, patched.packageName, log);
  signing(1);

  rm(decoded);
  fs.rmSync(unsigned, { force: true });

  return {
    changes: patched.changes,
    packageName: patched.packageName,
    outputPath,
    size: fs.statSync(outputPath).size,
    format: "apk",
  };
}
