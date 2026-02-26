#!/usr/bin/env bun

import { $ } from "bun"
import { mkdir } from "node:fs/promises"
import os from "node:os"
import path from "node:path"

const usage = `Usage:
  bun ./packages/mobile/script/add-icon.ts --source lucide --name pin

Options:
  --source <id>   Icon source preset: lucide | material-symbols | material-icons (default: lucide)
  --name <name>   Icon name in the selected source (required)
  --target <name> Kotlin icon/property name (default: PascalCase(name))
  --overwrite     Overwrite an existing icon file
`

const mobile = path.resolve(import.meta.dir, "..")
const icons = path.join(mobile, "app", "src", "main", "kotlin", "de", "chennemann", "opencode", "mobile", "icons")
const pkg = "de.chennemann.opencode.mobile.icons"
const iconPack = "Icons"
const tool = path.join(os.homedir(), ".opencode-mobile", "valkyrie")

const args = process.argv.slice(2)
const readArg = (key: string) => {
    const idx = args.indexOf(key)
    if (idx < 0) return undefined
    return args[idx + 1]
}

if (args.includes("-h") || args.includes("--help")) {
    console.log(usage)
    process.exit(0)
}

const presets: Record<string, string> = {
    lucide: "lucide",
    "material-symbols": "material-symbols",
    "material-icons": "material-symbols",
}

const sourceArg = readArg("--source") ?? "lucide"
const source = presets[sourceArg] ?? sourceArg
const name = readArg("--name")
const overwrite = args.includes("--overwrite")

if (!name) {
    console.error("Missing required --name\n")
    console.error(usage)
    process.exit(1)
}

const toPascal = (value: string) =>
    value
        .split(/[^a-zA-Z0-9]+/)
        .filter(Boolean)
        .map((x) => x[0].toUpperCase() + x.slice(1))
        .join("")

const target = readArg("--target") ?? toPascal(name)
const file = path.join(icons, `${target}.kt`)

if (!overwrite && (await Bun.file(file).exists())) {
    console.error(`Icon file already exists: ${file}`)
    console.error("Use --overwrite if you want to replace it.")
    process.exit(1)
}

const ensureValkyrie = async () => {
    const bin = path.join(tool, "bin", "valkyrie")
    if (await Bun.file(bin).exists()) return bin

    console.log("Installing Valkyrie CLI...")
    const releases = await fetch("https://api.github.com/repos/ComposeGears/Valkyrie/releases?per_page=30")
    if (!releases.ok) {
        throw new Error(`Failed to fetch Valkyrie releases: ${releases.status}`)
    }
    const data = (await releases.json()) as Array<{
        tag_name: string
        assets: Array<{ name: string; browser_download_url: string }>
    }>

    const rel = data.find((x) => x.tag_name.startsWith("cli-"))
    if (!rel) {
        throw new Error("Unable to find a Valkyrie CLI release")
    }

    const asset = rel.assets.find((x) => x.name.startsWith("valkyrie-cli-") && x.name.endsWith(".zip"))
    if (!asset) {
        throw new Error(`Unable to find CLI zip asset for ${rel.tag_name}`)
    }

    const dir = tool
    const zip = path.join(dir, "valkyrie-cli.zip")
    await mkdir(dir, { recursive: true })
    await Bun.write(zip, await (await fetch(asset.browser_download_url)).arrayBuffer())
    if (process.platform === "win32") {
        await $`powershell -NoProfile -Command "Expand-Archive -Path '${zip}' -DestinationPath '${dir}' -Force"`
    } else {
        await $`python -c "import zipfile; z=zipfile.ZipFile(r'${zip}'); z.extractall(r'${dir}')"`
    }

    if (!(await Bun.file(bin).exists())) {
        throw new Error("Valkyrie CLI install failed")
    }

    return bin
}

const bin = await ensureValkyrie()
const tmp = path.join(tool, "tmp")
const svg = path.join(tmp, `${target}.svg`)
const id = `${source}:${name}`
const url = `https://api.iconify.design/${encodeURIComponent(id)}.svg`

console.log(`Downloading ${id}...`)
const icon = await fetch(url)
if (!icon.ok) {
    throw new Error(`Failed to fetch icon ${id}: ${icon.status}`)
}

await mkdir(tmp, { recursive: true })
const body = await icon.text()
const box = body.match(/viewBox\s*=\s*"([^"]+)"/)
const part = box?.[1]?.trim().split(/\s+/)
const width = part?.[2] ?? "24"
const height = part?.[3] ?? width
const normalized = body
    .replace(/width\s*=\s*"[^"]*"/, `width="${width}"`)
    .replace(/height\s*=\s*"[^"]*"/, `height="${height}"`)
await Bun.write(svg, normalized)

if (!(await Bun.file(path.join(icons, "Icons.kt")).exists())) {
    await $`${bin} iconpack --output-path=${icons} --package-name=${pkg} --iconpack=${iconPack}`
}

await $`${bin} svgxml2imagevector --input-path=${svg} --output-path=${icons} --package-name=${pkg} --iconpack-name=${iconPack} --output-format=lazy-property --use-compose-colors=true --generate-preview=false --explicit-mode=false --trailing-comma=false --indent-size=4`

console.log(`Generated ${file}`)
