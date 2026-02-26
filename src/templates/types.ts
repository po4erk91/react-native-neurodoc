/** Page size in points (72 points = 1 inch) */
export interface PageSize {
  width: number; // default 595 (A4)
  height: number; // default 842 (A4)
}

/** Color as hex string: '#RRGGBB' */
export type Color = string;

/** Margins in points */
export interface Margins {
  top: number;
  right: number;
  bottom: number;
  left: number;
}

/** Font specification */
export interface FontSpec {
  /** Safe cross-platform PDF fonts */
  family: 'Helvetica' | 'Courier' | 'Times';
  size: number;
  bold?: boolean;
  italic?: boolean;
  color?: Color;
}

/** Horizontal alignment */
export type Alignment = 'left' | 'center' | 'right';

// ---- Layout Element Types ----

export interface TextElement {
  type: 'text';
  /** Static text or data-binding expression: "{{companyName}}" */
  content: string;
  font?: Partial<FontSpec>;
  alignment?: Alignment;
  /** Maximum width in points (text wraps). Defaults to available content width */
  maxWidth?: number;
  marginBottom?: number;
}

export interface ImageElement {
  type: 'image';
  /** File URL or data-binding expression: "{{logoUrl}}" */
  src: string;
  width: number;
  height: number;
  alignment?: Alignment;
  marginBottom?: number;
}

export interface LineElement {
  type: 'line';
  thickness?: number; // default 1
  color?: Color; // default '#CCCCCC'
  marginBottom?: number;
}

export interface SpacerElement {
  type: 'spacer';
  height: number;
}

export interface RectElement {
  type: 'rect';
  width: number;
  height: number;
  fillColor?: Color;
  borderColor?: Color;
  borderWidth?: number;
  cornerRadius?: number;
  marginBottom?: number;
}

export interface ColumnsElement {
  type: 'columns';
  /** Array of column definitions. Widths are proportional (e.g., [1, 2] = 33% and 67%). */
  columns: Array<{
    width: number;
    elements: TemplateElement[];
  }>;
  gap?: number; // horizontal gap between columns, points
  marginBottom?: number;
}

export interface TableColumn {
  header: string;
  /** Data-binding key for row data: "description", "quantity", "price" */
  key: string;
  width: number; // relative weight
  alignment?: Alignment;
}

export interface TableElement {
  type: 'table';
  columns: TableColumn[];
  /** Data binding key pointing to an array in the data: "items" */
  dataKey: string;
  headerFont?: Partial<FontSpec>;
  bodyFont?: Partial<FontSpec>;
  stripeColor?: Color;
  showGridLines?: boolean;
  gridLineColor?: Color;
  rowHeight?: number;
  marginBottom?: number;
}

export interface KeyValueElement {
  type: 'keyValue';
  entries: Array<{
    label: string;
    /** Data-binding expression or static text */
    value: string;
  }>;
  labelFont?: Partial<FontSpec>;
  valueFont?: Partial<FontSpec>;
  gap?: number; // gap between label and value
  marginBottom?: number;
}

export type TemplateElement =
  | TextElement
  | ImageElement
  | LineElement
  | SpacerElement
  | RectElement
  | ColumnsElement
  | TableElement
  | KeyValueElement;

// ---- Template Definition ----

export interface TemplateDefinition {
  pageSize?: PageSize;
  margins?: Margins;
  defaultFont?: Partial<FontSpec>;
  backgroundColor?: Color;
  /** Header elements (repeated on every page) */
  header?: TemplateElement[];
  /** Footer elements (repeated on every page) */
  footer?: TemplateElement[];
  /** Body elements (flows across pages with automatic page breaks) */
  body: TemplateElement[];
}
