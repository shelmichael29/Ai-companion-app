# F10 module scanning — 0.3.0-experimental (3)

## Controls
- **Scan All (47)** checks the entire candidate catalog, sequentially.
- **Choose modules or scan one** opens a list with equipment notes, **Select all**, **Clear selection**, checkboxes and a **Scan** button beside each module.
- **Scan Selected (N)** checks only the selection. An empty selection sends nothing.
- Selection and target controls are locked during operations. **Cancel diagnostic check** stops a scan and retains completed results.
- VIN reading always targets the DME, independently of the scan selection.

## Coverage and interpretation
47 **candidate addresses**, not 47 installed modules. The expected 26–30 installed ECUs depend on actual options and build. Some devices share an ECU/address or are sub-bus devices; this is not a complete inventory or a fault-code scan.
A positive diagnostic response and an explicit ECU rejection both demonstrate a responding ECU. Gateway routing errors are separate. Silence does **not** prove a module is absent.
One fresh TCP stream per module prevents late replies from being attributed to another ECU.
The existing 3 s diagnostic reply deadline and configurable 5 s connection default remain. A quiet candidate can consume the whole reply deadline. Each result logs elapsed milliseconds including connection; the summary logs total duration. Cancellation and cable-disconnect handling remain.

## Address basis
Address facts were checked against the F-series UDS routing table (`ZUORDNUNGSTABELLEUDS`) in [T_GRTB](https://github.com/emdzej/ediabasx-docs-sgbd/blob/b644de8fbfbb4b207f57794e3c7894dc1dc58627/docs/sgbd/T_GRTB.md#table-zuordnungstabelleuds). Shared platform entries are often labeled F01, F07 or F10 in the table; that label is not proof of installation in a specific F10.
Cross-check equipment applicability against BMW's [F10 vehicle and electronics training](https://www.manuallib.com/download/2023-10-17/BMW%20F10%E6%B1%BD%E8%BD%A6%E7%BB%B4%E4%BF%AE%E6%89%8B%E5%86%8C.pdf).
Names below are plain-English descriptions; SGBDs are reference identifiers only and are not bundled or executed. No raw address sweep, writable services or session changes are added.

| Name | Abbreviation | Hex address | Routing reference | Equipment |
| --- | --- | --- | --- | --- |
| Engine computer | DME | 0x12 | MSV90 | Standard |
| Transmission | EGS | 0x18 | GSB231 | Automatic transmission |
| Stability and braking | DSC | 0x29 | DSC_01 | Standard |
| Instrument cluster | KOMBI | 0x60 | KOMB01 | Standard |
| Junction box electronics | JBE | 0x00 | JBBF3 | Standard; parking sensors may be integrated |
| Airbag and crash safety | ACSM | 0x01 | ACSM3 | Standard |
| Steering column switches | SZL | 0x02 | SZL_01 | Equipment dependent |
| Surround and rear cameras | TRSVC | 0x06 | TRSVC01 | Camera option |
| Blind spot warning | SWW | 0x08 | HC2_01 | Lane change warning option |
| Central gateway | ZGW | 0x10 | ZGW_01 | Standard |
| Active steering actuator | IAS | 0x16 | ASA_01 | Integral active steering option |
| Fuel pump controller | EKPS | 0x17 | EKP301 | Equipment dependent |
| All wheel drive transfer case | VTG | 0x19 | LMV_01 | xDrive only |
| Integrated chassis management | ICM | 0x1C | ICMQL | Standard |
| Tire pressure monitoring | RDC | 0x20 | RDC_01 | Market and equipment dependent |
| Adaptive cruise radar | ACC | 0x21 | FRR_10 | Active cruise option |
| Rear seat entertainment | RSE | 0x26 | RSE_01 | Rear entertainment option |
| Electronic parking brake | EMF | 0x2A | EMF_10 | Standard |
| Rear wheel steering | HSR | 0x2B | HSR_01 | Integral active steering option |
| Parking maneuver assistant | PMA | 0x2C | PMA_10 | Parking assistant option |
| Electric power steering | EPS | 0x30 | EPS_01 | Steering system dependent |
| Phone and media interface | COMBOX | 0x36 | CMEDIA | Combox option |
| Audio amplifier | AMP | 0x37 | AMPT10 / AMPH10 | Diagnostic capable amplifier option |
| Vertical dynamics management | VDM | 0x39 | ICMV | Adaptive chassis option |
| Head up display | HUD | 0x3D | HUD_01 | Head up display option |
| Car access and start authorization | CAS | 0x40 | CAS4 | Standard |
| Roof electronics | FZD | 0x56 | FZD3 | Equipment dependent |
| Night vision | NVE | 0x57 | NIVI2 | Night vision option |
| Driver assistance camera | KAFAS | 0x5D | KAFAS01 | Driver assistance option |
| Gear selector | GWS | 0x5E | GWS_01 | Automatic transmission |
| High beam assistant | FLA | 0x5F | FLA_01 | High beam assistant option |
| Emergency call interface | ECALL | 0x61 | CECALL | Combox emergency call option |
| Head unit (CHAMP2 or CIC) | HU | 0x63 | CHAMP2 / CIC | Standard; shared address |
| Parking distance controller | PDC | 0x64 | PDC_01 | Separate parking controller if fitted |
| iDrive controller | ZBE | 0x67 | ZBE_01 | Equipment dependent |
| Power trunk controller | HKL | 0x6B | HKL_01 | Power trunk option |
| Driver seat memory | SMFA | 0x6D | FAS_01 | Seat memory option |
| Passenger seat memory | SMBF | 0x6E | BFS_01 | Seat memory option |
| Trailer controller | AHM | 0x71 | AHM_01 | Trailer option |
| Footwell and lighting electronics | FRM | 0x72 | FRM3 | Standard |
| Central information display | CID | 0x73 | CID_01 | Equipment dependent |
| Heating and air conditioning | IHKA | 0x78 | IHKA01 | Standard |
| Rear climate control | FKA | 0x79 | FKA_01 | Four zone climate option |
| Front left damper controller | EDC_FL | 0xA5 | RK_VL | Electronic damper option |
| Front right damper controller | EDC_FR | 0xA6 | RK_VR | Electronic damper option |
| Rear left damper controller | EDC_RL | 0xA7 | RK_HL | Electronic damper option |
| Rear right damper controller | EDC_RR | 0xA8 | RK_HR | Electronic damper option |

## Target handling
The phone's configured route gateway need not be the BMW gateway. The supplied hardware log showed failed attempts to the route gateway, followed by success to the discovered vehicle address.
The target changes only through editing or **Use as target**; there is no new automatic replacement. Explicit gateway selection now logs the previous and new target and displays a selection notice. A changed vehicle address requires fresh discovery/selection.

## Hardware evidence and next checks
The owner reported that build 0.2.0's DME VIN read and original four-module scan worked on the 2011 528i. The supplied log confirms discovery in 52 ms, TCP port check in 22 ms, connection test in 31 ms, gateway diagnostic response in 3 ms, and all four scan responses. The 101 ms from first to last scan result is not full scan duration because that build did not log the scan start.
The 47-candidate scan, individual selection, timing display and cancellation in build 0.3.0 still need hardware verification. Do not reduce timeouts based on only four responsive modules.

## Prompt / change record
- Date: 2026-11-21 (Arizona).
- Request: expand beyond four F10 modules; add individual/selected/all scanning; review response timing and clarify target changes.
- Build: 0.3.0-experimental (3).
- Scope: tester, ENET core and documentation only; no main-app changes.
- Status: **merged with owner approval** on 2026-11-21 (Arizona), into `experiment/bmw-enet-tester` only.
- Merge order: [#48](https://github.com/shelmichael29/Ai-companion-app/pull/48), then [#52](https://github.com/shelmichael29/Ai-companion-app/pull/52).
- Code merge: `4a279590e09f94400f78578972ba324be18ae7a5`; resulting code matched the tested feature branch. Main was verified unchanged.
- Build, unit tests and lint: **PASS**, [branch CI](https://github.com/shelmichael29/Ai-companion-app/actions/runs/44930835696) and [PR CI](https://github.com/shelmichael29/Ai-companion-app/actions/runs/44931119549).
- Expanded physical scan checks remain pending. PR #49 is a separate alternative implementation and was not merged.
