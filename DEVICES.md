# Tested phones

The app reads `devices.json` (same folder as this file) to know which phones the project has seen run. This table is the
same list in words.

| Phone | SoC | GPU | iOS 26 | iOS 16 | watchOS | Note |
| --- | --- | --- | --- | --- | --- | --- |
| Samsung SM-S938B (Galaxy S25 Ultra) | SM8750 | Adreno 830 | works | works | works | |
| any phone with an ARM Mali GPU | any | Mali | fails | not tested | fails | The GPU check fails: no VK_EXT_external_memory_host. Not supported yet. |
| any phone with an Imagination PowerVR GPU | any | PowerVR | fails | not tested | fails | The GPU check fails. Not supported yet. |
| any phone with a Samsung Xclipse GPU | any | Xclipse | fails | not tested | fails | The GPU check fails. Not supported yet. |

A row with a SoC is about that chip. A row without one is a GPU family and covers every phone whose GPU name contains it.
iOS 16 does not use the GPU, so it is matched on the SoC alone.

How a phone gets onto the list: after a guest has run on a phone for at least ten minutes and the phone's chip is not on
the list, the app offers a "works" report, a prefilled GitHub issue that you read and submit yourself. The issue is added
here and then closed.
