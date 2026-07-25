# Order Workbench Design QA

## Evidence

- Visual source of truth: `design/order-workbench-concept.png`
- Implemented desktop state: `implementation-orders-drawer-native.png`
- Combined comparison: `design-comparison.png`
- Responsive evidence: `implementation-orders-mobile.png`
- Exception workflow evidence: `implementation-exceptions.png`
- Desktop comparison viewport: 1536 × 1024 CSS px, DPR 1
- Mobile verification viewport: 390 × 844 CSS px
- Compared state: order list with `SO-202607230001` detail drawer open

The in-app browser was used first for interactive verification. Its temporary viewport override remained at 1265 × 720, so the native 1536 × 1024 comparison capture was produced through the project Playwright test runner. The source and implementation were then placed in one same-scale comparison image and visually inspected together.

## Comparison Findings

### Typography and content

- The page title, navigation, tabs, filters, table headers, status labels, fulfillment details, timeline, masked phone number, and monetary hierarchy match the approved concept.
- Platform codes are mapped to readable Chinese names in the drawer.
- The earlier unapproved page subtitle was removed.
- Order identifiers are kept on one line; dense date/time values use a smaller size without clipping.

### Layout and spacing

- The 164 px navy sidebar, sticky top bar, filter hierarchy, table density, and 450 px right drawer reproduce the target composition.
- Opening the drawer reserves its width in the main panel, so table columns remain visible instead of being obscured.
- Remaining differences are low-severity optical variations: the implementation filter content starts roughly 14 px farther left, and some drawer section gaps are slightly tighter. Neither changes hierarchy or usability.

### Colors, surfaces, icons, and imagery

- Navy navigation, blue primary actions, gray page surface, white panels, semantic green/orange/red statuses, borders, and restrained shadows follow the source palette.
- Ant Design Vue icons provide one consistent stroke family; active, notification, copy, close, search, and navigation states are present.
- Product rows use generated catalog assets with white backgrounds and suitable crops; no placeholder boxes, CSS art, or handcrafted SVG substitutes are used.

### Behavior and responsiveness

- Primary order-list filtering controls, order detail opening/closing, exception navigation, SKU mapping action, and status states work with realistic demo data.
- At 390 × 844, the navigation collapses to icons, the filter controls remain operable, and the table scrolls inside its container without body overflow.
- Automated checks cover desktop drawer behavior and mobile-width usability.
- Interactive browser inspection found no warning or error console entries.

## Fix History

- P2: removed extra title copy that shifted the approved hierarchy.
- P2: corrected filter height and vertical alignment drift.
- P2: fixed drawer width and prevented it from covering the table.
- P2: eliminated development HMR console noise during preview verification.
- P2: prevented order-number wrapping.
- P2: restored collapsed navigation icons on mobile.
- P3: retained minor horizontal and vertical spacing differences where the design intent and readability are unchanged.

final result: passed
