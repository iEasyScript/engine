# innoextract (vendored, Windows x86)

`innoextract.exe` from the official 1.9 Windows release
(<https://constexpr.org/innoextract/files/innoextract-1.9-windows.zip>),
sha256 `946bd06d8b3722a791fea4d84f496139eef6faef61640924cb72f5f1998a68e1`.

The Windows launcher embeds this binary and stages it to a temp dir to unpack
Jagex's `RuneScape-Setup.exe`, which is an Inno Setup installer no Windows host
can open on its own. Running that installer instead is not an option: it wants
elevation, installs into `Program Files`, and finishes by starting the Jagex
launcher, which begins a multi-gigabyte live cache download.

Redistribution is covered by the zlib licence in `LICENSE.txt`; the licences of
its statically linked dependencies (Boost, liblzma, libstdc++ with the GCC
Runtime Library Exception) ship in the upstream archive linked above.

Upstream supports Inno Setup 1.2.10 through 6.0.5. If Jagex ever ships an
installer past that range, replace this binary with a newer upstream release —
the launcher only ever shells out to it, so nothing else has to change.
