export const PATCHED_SUFFIX = ".patched";

const PACKAGE_NAME = /^[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z0-9_]+)+$/;

export function withSuffix(packageName, suffix = PATCHED_SUFFIX) {
  const name = String(packageName || "").trim();
  if (!name || !suffix || name.endsWith(suffix)) return name;
  const next = `${name}${suffix}`;
  if (!PACKAGE_NAME.test(next)) throw new Error(`The package name cannot take ${suffix}.`);
  return next;
}

export function replacePrefix(value, oldName, newName) {
  if (!oldName || oldName === newName) return value;
  if (value === oldName) return newName;
  if (value.startsWith(`${oldName}.`)) return `${newName}${value.slice(oldName.length)}`;
  return value;
}

export function replaceList(value, oldName, newName) {
  return String(value)
    .split(";")
    .map((part) => {
      const leading = part.match(/^ */)?.[0] || "";
      const trailing = part.match(/ *$/)?.[0] || "";
      return `${leading}${replacePrefix(part.trim(), oldName, newName)}${trailing}`;
    })
    .join(";");
}

const IDENTITY_ATTRIBUTES = ["authorities", "permission", "readPermission", "writePermission", "targetPackage", "taskAffinity", "process"];

export function renameManifestPackage(xml, suffix = PATCHED_SUFFIX) {
  const declared = String(xml).match(/\bpackage="([^"]+)"/);
  if (!declared) return { xml, renamed: false, packageName: "" };
  const oldName = declared[1];
  const packageName = withSuffix(oldName, suffix);
  if (packageName === oldName) return { xml, renamed: false, packageName, oldName };
  let next = xml.replace(/android:name="\./g, `android:name="${oldName}.`);
  next = next.replace(
    /<(permission|uses-permission|uses-permission-sdk-23|permission-tree|permission-group)\b[^>]*(?:\/>|>)/g,
    (tag) => tag.replace(/android:name="([^"]*)"/, (_, value) => `android:name="${replacePrefix(value, oldName, packageName)}"`),
  );
  for (const attribute of IDENTITY_ATTRIBUTES) {
    const pattern = new RegExp(`android:${attribute}="([^"]*)"`, "g");
    next = next.replace(pattern, (_, value) => `android:${attribute}="${replaceList(value, oldName, packageName)}"`);
  }
  next = next.replace(/\s+android:sharedUserId="[^"]*"/g, "");
  next = suffixOwnedNames(next, packageName, suffix);
  next = suffixAuthorities(next, suffix);
  next = next.replace(/\bpackage="[^"]+"/, `package="${packageName}"`);
  return { xml: next, renamed: true, packageName, oldName };
}

function suffixOwnedNames(xml, newName, suffix) {
  const declared = new Set();
  const tags = /<(permission|permission-tree|permission-group)\b[^>]*>/g;
  let match = tags.exec(xml);
  while (match) {
    const name = match[0].match(/\bandroid:name="([^"]+)"/);
    const value = name ? name[1] : "";
    const owned = value && !value.startsWith("android.") && value !== newName && !value.startsWith(`${newName}.`) && !value.endsWith(suffix);
    if (owned) declared.add(value);
    match = tags.exec(xml);
  }
  let next = xml;
  for (const value of [...declared].sort((left, right) => right.length - left.length)) {
    next = next.replaceAll(`"${value}"`, `"${value}${suffix}"`);
  }
  return next;
}

function suffixAuthorities(xml, suffix) {
  return xml.replace(/\bandroid:authorities="([^"]*)"/g, (_, value) => {
    const next = value.split(";").map((part) => {
      const core = part.trim();
      if (!core || core.endsWith(suffix) || core.includes(`${suffix}.`)) return part;
      return part.replace(core, `${core}${suffix}`);
    }).join(";");
    return `android:authorities="${next}"`;
  });
}
