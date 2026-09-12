//! Update-day check: report how many sites each patch target resolves to in a
//! client or launcher binary, without running anything.
//!
//!   cargo run --example scan -- ../../../data/client/*/rs2client
//!
//! A client binary must show exactly one login and one JS5 modulus site and at
//! least one HTTP port site; a launcher binary exactly one launcher modulus and
//! one codebase regex site. Anything else means the patterns need re-deriving
//! against the new build before the patchers can be trusted. Exits non-zero if
//! any input falls short.

use projectx_patcher_common as common;

fn main() {
    let mut any_bad = false;

    for path in std::env::args().skip(1) {
        let data = match std::fs::read(&path) {
            Ok(data) => data,
            Err(e) => {
                eprintln!("{}: {}", path, e);
                any_bad = true;
                continue;
            }
        };

        println!("\n=== {} ({} bytes) ===", path, data.len());

        let login = count(&data, common::LOGIN_MODULUS_PREFIX);
        let js5 = count(&data, common::JS5_MODULUS_PREFIX);
        let launcher = count(&data, common::LAUNCHER_MODULUS_PREFIX);
        let codebase = count(&data, common::CODEBASE_REGEX);
        for (label, n) in [
            ("login modulus", login),
            ("js5 modulus", js5),
            ("launcher modulus", launcher),
            ("codebase regex", codebase),
        ] {
            println!("  {:18} {} site(s)", label, n);
        }

        let sites = common::find_port_sites(&data);
        println!("  {:18} {} site(s)", "http port", sites.len());
        for site in &sites {
            println!(
                "      offset=0x{:x} immediate=0x{:x} width={}",
                site.offset,
                site.patch_offset(),
                site.imm_width
            );
        }

        let ok = if login == 1 {
            js5 == 1 && !sites.is_empty()
        } else if launcher == 1 {
            codebase == 1
        } else {
            false
        };
        println!("  => {}", if ok { "OK" } else { "NEEDS ATTENTION" });
        any_bad |= !ok;
    }

    if any_bad {
        std::process::exit(1);
    }
}

fn count(haystack: &[u8], needle: &[u8]) -> usize {
    memchr::memmem::find_iter(haystack, needle).count()
}
