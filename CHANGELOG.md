# Changelog

All notable changes to this project will be documented in this file. This changelog follows the conventions of [keepachangelog.com](http://keepachangelog.com/).

## Unreleased

- #65: return URL in lookup op
- Fix #68: classpath op in describe

## [0.0.7] - 2022-11-27

- Fix #59: don't emit newline with cider pprint function is used

## [0.0.6] - 2022-03-10

- Bump SCI to new org

## [0.0.5] - 2022-10-07

- Add middleware #11 (@phronmophobic)

## [0.0.4] - 2021-05-16

### Enhanced

- Implement `cider-nrepl` `info` / `lookup` op [#30](https://github.com/babashka/babashka.nrepl/issues/30) [(@brdloush)](https://github.com/brdloush)

### Enhanced

- Implement pprint support [#18](https://github.com/babashka/babashka.nrepl/issues/18) ([@kolharsam](https://github.com/kolharsam), [@grazfather](https://github.com/grazfather), [@bbatsov](https://github.com/bbatsov))

## [0.0.3] - 2020-05-18

### Fixed
- Fix print stdout without trailing \n not captured and sent to nrepl. - #8
- Fix truncated nrepl output. - #9

## [0.0.2] - 2020-05-17

### Fixed
- Fix binding conveyance of sci vars. - #2

## [0.0.1] - 2020-05-13
Initial release

[Unreleased]: https://github.com/babashka/babashka.nrepl/compare/v0.0.3...HEAD
[0.0.3]: https://github.com/babashka/babashka.nrepl/compare/v0.0.2...v0.0.3
[0.0.2]: https://github.com/babashka/babashka.nrepl/compare/v0.0.1...v0.0.2
[0.0.1]: https://github.com/babashka/babashka.nrepl/tree/v0.0.1
