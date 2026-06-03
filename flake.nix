{
  description = "Grimmory Library Organizer - Packaged with native Node portal and automated Python metadata daemon on NixOS";

  inputs = {
    nixpkgs.url = "github:nixos/nixpkgs/nixos-unstable";
    flake-utils.url = "github:numtide/flake-utils";
  };

  outputs = { self, nixpkgs, flake-utils }:
    flake-utils.lib.eachDefaultSystem (system:
      let
        pkgs = import nixpkgs { inherit system; };

        # 1. Package the Python Librarian Daemon
        grimmory-daemon = pkgs.writers.writePython3Bin "grimmory-daemon" {
          libraries = with pkgs.python3Packages; [
            pillow
            pytesseract
            beautifulsoup4
            pypdf
            opencv4
          ];
        } (builtins.readFile ./src/components/CodeGenerator.tsx); # Note to users to download their custom config or use default

        # 2. Package the full React + Express web server
        grimmory-web = pkgs.buildNpmPackage {
          pname = "grimmory-web";
          version = "2.5.0";

          src = ./.;

          npmDepsHash = "sha256-AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="; # Placeholder, can be overridden with a fixed derivation or used locally

          # Nix builders skip dynamic network calls; local build can bypass
          dontNpmBuildPrereq = true;

          buildPhase = ''
            npm run build
          '';

          installPhase = ''
            mkdir -p $out/lib/node_modules/grimmory
            cp -r * $out/lib/node_modules/grimmory/
            mkdir -p $out/bin
            ln -s $out/lib/node_modules/grimmory/dist/server.cjs $out/bin/grimmory-web
          '';
        };

      in {
        packages = {
          default = grimmory-daemon;
          inherit grimmory-daemon grimmory-web;
        };

        devShells.default = pkgs.mkShell {
          name = "grimmory-development-shell";

          buildInputs = [
            pkgs.nodejs_20
            pkgs.nodePackages.npm
            pkgs.tesseract
            pkgs.tesseract-ocr-eng
            pkgs.tesseract-ocr-ukr

            (pkgs.python3.withPackages (ps: with ps; [
              pillow
              pytesseract
              beautifulsoup4
              pypdf
              opencv4
              pip
              virtualenv
            ]))
          ];

          shellHook = ''
            export TESSDATA_PREFIX="$(pwd)/.tessdata"
            mkdir -p .tessdata
            ln -sf ${pkgs.tesseract-ocr-eng}/share/tessdata/eng.traineddata .tessdata/
            ln -sf ${pkgs.tesseract-ocr-ukr}/share/tessdata/ukr.traineddata .tessdata/

            echo "========================================================="
            echo "  📜 GRIMMORY DEVELOPMENT FLAKE SHELL ACTIVE"
            echo "  System: ${system}"
            echo "========================================================="
            echo " Available engines: Node \${pkgs.nodejs_20.version}, Python 3 \${pkgs.python3.version}"
            echo " Integrated dictionaries: English (eng), Ukrainian (ukr)"
            echo " Run 'npm run dev' to boot the sandboxed portal locally!"
            echo "========================================================="
          '';
        };
      }) // {
        # 3. Declarative NixOS System Modules for Nix Flakes
        nixosModules.default = { config, lib, pkgs, ... }:
          let
            cfg = config.services.grimmory;
          in {
            options.services.grimmory = {
              daemon = {
                enable = lib.mkEnableOption "Grimmory Library Metadata Organizer Automation Daemon";
                
                inputDir = lib.mkOption {
                  type = lib.types.str;
                  default = "/var/lib/grimmory/input";
                  description = "Input folder monitored for unorganized scanned book binaries (.pdf, .epub, .djvu).";
                };

                outputDir = lib.mkOption {
                  type = lib.types.str;
                  default = "/var/lib/grimmory/sorted";
                  description = "Destination parent directory where sorted books get organized into catalogued indices.";
                };

                geminiModel = lib.mkOption {
                  type = lib.types.str;
                  default = "gemini-3.5-flash";
                  description = "Target Gemini LLM core to run when confidence thresholds decline.";
                };

                confidenceThreshold = lib.mkOption {
                  type = lib.types.int;
                  default = 70;
                  description = "Under this score, the daemon scale queries from thin slices (2 pages) up to 10 pages.";
                };

                apiKeyFile = lib.mkOption {
                  type = lib.types.nullOr lib.types.path;
                  default = null;
                  description = "File path containing the secure GEMINI_API_KEY environment variable.";
                };

                interval = lib.mkOption {
                  type = lib.types.str;
                  default = "*:0/15"; # Every 15 minutes
                  description = "Systemd OnCalendar timer interval expression.";
                };
              };

              web = {
                enable = lib.mkEnableOption "Grimmory Portal UI Full-Stack Node Web Application";
                
                port = lib.mkOption {
                  type = lib.types.port;
                  default = 3000;
                  description = "Address port where the local reverse proxy links.";
                };

                apiKeyFile = lib.mkOption {
                  type = lib.types.nullOr lib.types.path;
                  default = null;
                  description = "File path containing the secure GEMINI_API_KEY for server-bound requests.";
                };
              };
            };

            config = lib.mkMerge [
              # Optional configuration for automated daemon service
              (lib.mkIf cfg.daemon.enable {
                systemd.services.grimmory-daemon = {
                  description = "Grimmory Automatic metadata directory sync background service";
                  after = [ "network.target" ];
                  
                  serviceConfig = {
                    Type = "oneshot";
                    User = "root";
                    ExecStart = ''
                      ${pkgs.writers.writePython3Bin "grimmory-librarian-run" {
                        libraries = with pkgs.python3Packages; [ pillow pytesseract beautifulsoup4 pypdf opencv4 ];
                      } (builtins.readFile ./src/components/CodeGenerator.tsx)}/bin/grimmory-librarian-run \
                        --input "${cfg.daemon.inputDir}" \
                        --output "${cfg.daemon.outputDir}" \
                        --model "${cfg.daemon.geminiModel}" \
                        --threshold "${toString cfg.daemon.confidenceThreshold}"
                    '';
                    EnvironmentFiles = lib.optional (cfg.daemon.apiKeyFile != null) cfg.daemon.apiKeyFile;
                  };

                  environment = {
                    TESSDATA_PREFIX = "${pkgs.tesseract-ocr-eng}/share/tessdata:${pkgs.tesseract-ocr-ukr}/share/tessdata";
                  };
                };

                systemd.timers.grimmory-daemon = {
                  description = "Timer trigger for Grimmory Library metadata daemon sync frequency";
                  wantedBy = [ "timers.target" ];
                  timerConfig = {
                    OnCalendar = cfg.daemon.interval;
                    Persistent = true;
                  };
                };
              })

              # Optional configuration for web portal service
              (lib.mkIf cfg.web.enable {
                systemd.services.grimmory-web = {
                  description = "Grimmory Interactive Portal Web Server";
                  after = [ "network.target" ];
                  wantedBy = [ "multi-user.target" ];

                  serviceConfig = {
                    Type = "simple";
                    User = "nobody";
                    WorkingDirectory = "/var/lib/grimmory-web";
                    ExecStart = "${pkgs.nodejs_20}/bin/node /var/lib/grimmory-web/dist/server.cjs";
                    Restart = "on-failure";
                    EnvironmentFiles = lib.optional (cfg.web.apiKeyFile != null) cfg.web.apiKeyFile;
                  };

                  environment = {
                    PORT = toString cfg.web.port;
                    NODE_ENV = "production";
                  };
                };
              })
            ];
          };
      };
}
