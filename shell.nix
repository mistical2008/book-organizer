{ pkgs ? import <nixpkgs> {} }:

let
  tesseract-custom = pkgs.tesseract.override {
    enableLanguages = [ "eng" "ukr" "srp" "srp_latn" ];
  };

  # Custom python with packages
  pythonWithPackages = pkgs.python3.withPackages (ps: with ps; [
    pillow
    pytesseract
    beautifulsoup4
    pypdf
    opencv4
    pip
    virtualenv
  ]);
in
pkgs.mkShell {
  name = "librarian-dev-shell";

  buildInputs = [
    # Full-Stack Web Environment
    pkgs.nodejs_20
    pkgs.nodePackages.npm

    # Python Environment & Core Packages
    pythonWithPackages

    # System Utilities for OCR
    tesseract-custom
    pkgs.poppler-utils
    pkgs.djvulibre
  ];

  # Expose Tesseract dictionaries to pytesseract on NixOS
  shellHook = ''
    export TESSDATA_PREFIX="${tesseract-custom}/share/tessdata"
    
    # We create a symlink to combine dictionaries so tesseract can find all of them
    mkdir -p .tessdata
    ln -sf ${tesseract-custom}/share/tessdata/eng.traineddata .tessdata/
    ln -sf ${tesseract-custom}/share/tessdata/ukr.traineddata .tessdata/
    ln -sf ${tesseract-custom}/share/tessdata/srp.traineddata .tessdata/
    ln -sf ${tesseract-custom}/share/tessdata/srp_latn.traineddata .tessdata/
    export TESSDATA_PREFIX="$(pwd)/.tessdata"

    echo ""
    echo "========================================================="
    echo "  📜 LIBRARIAN ENVIRONMENT SHELL FOR NIXOS ACTIVE"
    echo "========================================================="
    echo " NixOS requires declaring or compiling dependencies."
    echo " We have injected nodejs, npm, tesseract, and python"
    echo " directly into your temporary environment session."
    echo ""
    echo " 1. Running the Full-Stack Web Application:"
    echo "    $ npm install"
    echo "    $ export GEMINI_API_KEY='your-key-here'"
    echo "    $ npm run dev"
    echo ""
    echo " 2. Running the Python Daemon Client (librarian.py):"
    echo "    $ python3 -m venv .venv --system-site-packages"
    echo "    $ source .venv/bin/activate"
    echo "    $ pip install google-genai"
    echo "    $ python librarian.py"
    echo ""
    echo " 3. Declaring Systemd service on NixOS:"
    echo "    Since NixOS is declarative, do not run standard systemctl."
    echo "    Instead, paste the nix daemon configuration template from"
    echo "    the 'Methodology Docs' tab in the app UI!"
    echo "========================================================="
    echo ""
  '';
}
