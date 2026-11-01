/** Run packaged CoreProtect and the test mod on a fresh dedicated NeoForge server. */
import { createWriteStream } from 'node:fs';
import { cp, mkdir, readFile, writeFile } from 'node:fs/promises';
import { execFileSync, spawn } from 'node:child_process';
import { join } from 'node:path';
import { createInterface } from 'node:readline';
import { once } from 'node:events';
import { Readable } from 'node:stream';
import { pipeline } from 'node:stream/promises';
import { fileURLToPath } from 'node:url';

const root = fileURLToPath(new URL('../../../', import.meta.url));
const properties = Object.fromEntries((await readFile(join(root, 'gradle.properties'), 'utf8'))
    .split(/\r?\n/).filter(line => line.includes('=') && !line.startsWith('#'))
    .map(line => { const i = line.indexOf('='); return [line.slice(0, i), line.slice(i + 1)]; }));
const work = join(root, 'build', 'smoke');
const server = join(work, 'server');
const mods = join(server, 'mods');
await mkdir(mods, { recursive: true });
const java = process.env.JAVA_HOME ? join(process.env.JAVA_HOME, 'bin', process.platform === 'win32' ? 'java.exe' : 'java') : 'java';
const installer = join(work, 'installer.jar');
const response = await fetch(`https://maven.neoforged.net/releases/net/neoforged/neoforge/${properties.neo_version}/neoforge-${properties.neo_version}-installer.jar`,
    { signal: AbortSignal.timeout(180000) });
if (!response.ok) throw new Error(`NeoForge installer download failed: ${response.status}`);
await pipeline(Readable.fromWeb(response.body), createWriteStream(installer));
execFileSync(java, ['-jar', installer, '--installServer'],
    { cwd: server, timeout: 360000, stdio: 'inherit', windowsHide: true });
const modJar = `coreprotect-${properties.mod_version}.jar`;
await cp(join(root, 'build', 'libs', modJar), join(mods, modJar));
await cp(join(root, 'build/testmods/coreprotect-test.jar'), join(mods, 'coreprotect-test.jar'));
await writeFile(join(server, 'eula.txt'), 'eula=true\n');
await writeFile(join(server, 'server.properties'),
    'server-ip=127.0.0.1\nserver-port=25586\nonline-mode=false\nenforce-secure-profile=false\n'
    + 'view-distance=2\nsimulation-distance=2\nspawn-protection=0\nmax-players=2\n'
    + 'pause-when-empty-seconds=-1\nlevel-type=minecraft:flat\n');
await writeFile(join(server, 'user_jvm_args.txt'), '-Xms256M\n-Xmx1G\n');

async function boot(expected, suffix) {
    const argsFile = process.platform === 'win32' ? 'win_args.txt' : 'unix_args.txt';
    const child = spawn(java, ['@user_jvm_args.txt', `@libraries/net/neoforged/neoforge/${properties.neo_version}/${argsFile}`, 'nogui'],
        { cwd: server, stdio: ['pipe', 'pipe', 'pipe'], windowsHide: true });
    const log = createWriteStream(join(work, `server-output${suffix}.log`));
    let success = false, ready = false, failure = false, stopping = false;
    const timeout = setTimeout(() => child.kill(), 180000);
    for (const stream of [child.stdout, child.stderr]) {
        const lines = createInterface({ input: stream });
        lines.on('line', line => {
            console.log(line);
            log.write(line + '\n');
            ready ||= line.includes('Done (');
            success ||= line.includes(expected);
            failure ||= line.includes('COREPROTECT_SMOKE_FAILED');
            if (ready && success && !stopping) {
                stopping = true;
                child.stdin.write('stop\n');
            }
        });
    }
    try {
        const [code] = await once(child, 'close');
        if (!ready || !success || failure || code !== 0) {
            throw new Error(`Dedicated smoke failed: ready=${ready}, success=${success}, failure=${failure}, exit=${code}`);
        }
    } finally {
        clearTimeout(timeout);
        if (child.exitCode === null && child.signalCode === null) child.kill();
        log.end();
    }
}
await boot('COREPROTECT_SMOKE_PASSED', '');
await boot('COREPROTECT_RESTART_PASSED', '-restart');
console.log('Dedicated block logging, rollback/restore, and restart persistence smoke passed.');
