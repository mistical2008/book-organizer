# Grimmory: Ukrainian Book Cataloger & Folder Sorter 📚

Grimmory is a self-deactivating library organization daemon. It recursively scans unstructured folders of digital books (`.pdf`, `.djvu`, `.epub`, `.fb2`, `.mobi`) to sort them into nested directories structured by **`${Author} - ${Title} (${Year})`**.

This system implements a transactional, staged data fetching pipeline engineered to stay completely within free API tiers while preventing data loss or redos with durable SQLite state management.

---

## 🏗️ Multi-Table Staging Architecture

The core full-stack backend engine is powered by **Clojure & Babashka** (`src/clojure/grimmory/server.clj` and `babashka/container.clj`) handling the entire folder scanning and book cataloging pipeline. It stores and persists transactional states across structured record registries in `/data/state.json` to isolate side effects:

1. **`scanned_books`**: Stores all mapped file scopes with their detected local ISBN tags (if any) to prevent redundant OCR scanning if the service is interrupted.
2. **`isbn_requests`**: A dedicated queue tracking query records targeting the free **Google Books public API**.
3. **`ai_categorization`**: A dedicated queue containing book text samples waiting to be grouped and requested from **Gemini AI** as a fallback. Supports context optimization batch sizes.
4. **`file_organization`**: The definitive repository storing target destinations, parsed years, metadata categories, and confidence levels. This serves as the source of truth for the physical file copy stage.

---

## ⚡ The Standard 3-Phase Algorithm

```
📂 [Source Directory]
  │
  ├─── Phase 1: Directory Recursion & Pre-Filter Scan
  │     ├── OCR First 10 Pages (Tesseract UKR/ENG)
  │     ├── Match ISBN regex patterns
  │     └── Populate scanned_books -> (Queue isbn_requests OR ai_categorization)
  │
  ├─── Phase 2: Target Database Generation (Compilation)
  │     ├── Phase 2.1: Google Books ISBN Match (Free Direct Lookups)
  │     └── Phase 2.2: Gemini LLM Fallback (Context thinning & dynamic batch cache)
  │           └── Populate file_organization (Destinations & Structured Meta)
  │
  └─── Phase 3: Transactional Copy & Sidecar Sign-off
        ├── Create folders -> Copy book files
        ├── Write companion 'metadata.json' sidecars
        └── Self-destruct systemd timer dynamically when unprocessed queue is empty
```

---

## 🛠️ Step-by-Step Setup Instructions

### 1. Prerequisite Packages Installation

Make sure your machine has native document encoders and OCR packages installed:

#### Ubuntu / Debian Desktop & Server
```bash
# Install Tesseract OCR Engine and language support files (Ukrainian and English)
sudo apt-get update
sudo apt-get install -y tesseract-ocr tesseract-ocr-ukr tesseract-ocr-eng poppler-utils djvulibre-bin

# Install core python libraries
pip install google-genai opencv-python pillow pytesseract ebooklib beautifulsoup4 pypdf
```

#### Clojure, ClojureScript & Babashka Stack
For developers wanting a purely functional Lisp stack:
1. **Babashka Engine**: Install the lightweight `bb` interpreter for scripting and task integration:
   ```bash
   curl -sLO https://raw.githubusercontent.com/babashka/babashka/master/install && chmod +x install && sudo ./install
   ```
2. **Clojure CLI Tools**: Ensure you have Clojure and standard JVM runtimes installed to run compilation tasks.
3. **Project Files Structure**:
   - `bb.edn`: Defines project paths, dependencies (`cheshire`, `org.babashka/http-client`), and daemon launcher shortcuts.
   - `src/clojure/grimmory/server.clj`: A Ring-compliant, high-performance Clojure server handling API routers, folder scanning, Google Books lookup, and Gemini API bindings.
   - `src/cljs/grimmory/core.cljs`: Reactive ClojureScript frontend using the Reagent model (Clojure interface layer for React).
   - `babashka/container.clj`: Standalone binary and container execution suite controlling schedules, health verification, and temporary cache sweeps.

To boot the Clojure backend and scheduling agent, simply invoke Babashka:
```bash
# Start the Ring web server on port 3000
bb server

# Start the automated scanning daemon
bb daemon

# Start the supervisor loop directly via the container orchestrator
bb babashka/container.clj run
```

---

#### NixOS (Declarative Module & Development Shell)

For NixOS systems, you can install the required packages on-the-fly using a development shell, or configure the service declarively in your system configurations.

##### A. Dynamic Shell Environment (`shell.nix`)
Create a `shell.nix` inside your repository directory to load all native packages and dependency libraries instantly environment-isolated:

```nix
{ pkgs ? import <nixpkgs> {} }:
pkgs.mkShell {
  buildInputs = with pkgs; [
    nodejs_20
    nodePackages.npm
    tesseract
    tesseract-ocr-eng
    tesseract-ocr-ukr
    poppler_utils
    djvulibre
    python311
    python311Packages.pytesseract
    python311Packages.pillow
    python311Packages.beautifulsoup4
    python311Packages.pypdf
  ];
}
```
Run `nix-shell` inside this folder to drop into the isolated terminal environment.

##### B. Declarative Configuration System Module
Add the modern unified service configuration to `/etc/nixos/configuration.nix`:

```nix
{ config, pkgs, ... }: {
  # Enable the Grimmory Web Portal with its background monitoring daemon
  services.grimmory = {
    enable = true;
    port = 3000;
    
    # Secure API credentials configuration path file (contains GEMINI_API_KEY="...")
    apiKeyFile = "/etc/secrets/gemini-api.env";
  };
}
```

---

#### GNU Guix (Pure shell Environment & Shepherd Service)

GNU Guix focuses on purely functional package deployment. You can run Grimmory in a isolated container environment, or configure it on a Guix System as a native Shepherd service.

##### A. Ephemeral Pure Environment (`guix shell`)
Launch a sandboxed environment containing all native binary wrappers and dependencies with a single command:

```bash
guix shell --pure node tesseract tesseract-ukr tesseract-eng poppler djvulibre python python-pillow python-pytesseract python-beautifulsoup4 -- python3 librarian.py
```

##### B. Declarative System Service Configuration
Declare Grimmory as a custom Shepherd service inside your `/etc/config.scm` boot configuration file:

```scheme
(use-modules (gnu services)
             (gnu services shepherd)
             (gnu packages ocr)
             (gnu packages pdf)
             (gnu packages python)
             (gnu packages python-xyz))

(define grimmory-shepherd-service
  (shepherd-service
    (provision '(grimmory-librarian))
    (documentation "Grimmory Library Metadata Organizer Clojure-Babashka Daemon active")
    (requirement '(networking))
    (start #~(make-forkexec-constructor
              (list (string-append #$babashka "/bin/bb")
                    "babashka/container.clj" "run")
              #:environment-variables
              (list "GEMINI_API_KEY=your-api-key-here")
              #:directory "/var/lib/grimmory-web"
              #:user "root"))
    (stop #~(make-kill-destructor))))
```

---

#### Arch Linux (Native package configuration & systemd timers)

Arch Linux provides cutting-edge native packages along with community-maintained OCR engines in the Arch User Repository (AUR).

##### A. Install Native Packages from Official Repositories & AUR
Run the package manager to install the runtime dependencies:

```bash
# Update mirrors and install core utility packages
sudo pacman -Syu
sudo pacman -S tesseract tesseract-data-eng poppler djvulibre nodejs npm python python-pip python-pillow python-beautifulsoup4

# Install Ukrainian Tesseract training data from AUR 
# (You can use any AUR helper such as yay or paru)
yay -S tesseract-data-ukr
```

##### B. Running the Daemon Services via systemd
Once your dependencies are in place, copy your script, populate `/etc/systemd/system/grimmory-librarian.service` (see Section 3 below), and activate your timer:

```bash
# Reload systemd config
sudo systemctl daemon-reload

# Activate and start the polling timer
sudo systemctl enable --now grimmory-librarian.timer
```

---

### 2. Configure Your GEMINI API Key

To run AI categorization fallbacks, ensure the `GEMINI_API_KEY` is loaded inside your context shell environment:

```bash
# Temporary shell load
export GEMINI_API_KEY="your-api-key-here"

# To persist on login, add to .bashrc / .zshrc:
echo 'export GEMINI_API_KEY="your-api-key-here"' >> ~/.bashrc
```

---

### 3. Service Daemon Deployment (Systemd)

To deploy the daemon as an automated systemd timer:

1. Position the compiled Grimmory folder inside your chosen installation path:
    ```bash
    sudo mkdir -p /var/lib/grimmory-web
    sudo cp -r . /var/lib/grimmory-web
    ```

2. Register the service module in `/etc/systemd/system/grimmory-librarian.service`:
    ```ini
    [Unit]
    Description=Grimmory Library Metadata Organizer Service
    After=network.target

    [Service]
    Type=simple
    User=root
    WorkingDirectory=/var/lib/grimmory-web
    ExecStart=/usr/bin/bb babashka/container.clj run
    Environment=GEMINI_API_KEY=your-api-key-here
    Restart=on-failure
    RestartSec=30s
    ```

3. Configure the periodic timer module in `/etc/systemd/system/grimmory-librarian.timer`:
    ```ini
    [Unit]
    Description=Run Grimmory Library Scan Periodically

    [Timer]
    OnCalendar=*-*-* *:00:00
    Persistent=true

    [Install]
    WantedBy=timers.target
    ```

4. Enable and boot the timer:
    ```bash
    sudo systemctl daemon-reload
    sudo systemctl enable --now grimmory-librarian.timer
    ```

5. Monitor runtime progress:
    ```bash
    # View system logs
    journalctl -u grimmory-librarian.service -f
    ```

---

## 🔮 Interactive Sandbox Testing

Open the **Live Sandbox** inside the Web application to simulate scanning, folder pickers, OCR runs, Google Books matching, Gemini context caching, and the live SQLite table grids in real-time under a fully localized client mockup dashboard!
