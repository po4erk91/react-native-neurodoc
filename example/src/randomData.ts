// Random data generators for PDF template demos

const pick = <T>(arr: T[]): T => arr[Math.floor(Math.random() * arr.length)]!;
const randInt = (min: number, max: number) => Math.floor(Math.random() * (max - min + 1)) + min;
const fmt = (n: number) => `$${n.toLocaleString('en-US', { minimumFractionDigits: 2 })}`;

const firstNames = ['James', 'Maria', 'David', 'Elena', 'Michael', 'Sofia', 'Daniel', 'Olivia', 'Lucas', 'Emma', 'Nathan', 'Ava', 'Ethan', 'Mia', 'Alex', 'Zoe'];
const lastNames = ['Anderson', 'Martinez', 'Thompson', 'Nakamura', 'Williams', 'Petrov', 'Garcia', 'Chen', 'O\'Brien', 'Kim', 'Johansson', 'Patel', 'Rossi', 'Santos', 'Muller', 'Lee'];
const streets = ['Main St', 'Oak Ave', 'Elm Blvd', 'Maple Dr', 'Cedar Ln', 'Pine Rd', 'Birch Way', 'Walnut Ct', 'Sunset Blvd', 'Park Ave', 'Broadway', 'Market St'];
const cities = ['New York, NY', 'San Francisco, CA', 'Chicago, IL', 'Austin, TX', 'Seattle, WA', 'Denver, CO', 'Boston, MA', 'Portland, OR', 'Miami, FL', 'Atlanta, GA', 'Nashville, TN', 'Phoenix, AZ'];
const companies = ['Acme Corp', 'Nexus Labs', 'Vertex Inc', 'Zenith Tech', 'Polaris Systems', 'Atlas Group', 'Titan Solutions', 'Orion Digital', 'Quantum Works', 'Apex Dynamics', 'Echo Ventures', 'Nova Industries'];
const domains = ['corp.com', 'io', 'tech', 'co', 'dev', 'net', 'solutions.com'];
const colors = ['#1A1A2E', '#2C3E50', '#1B4F72', '#6B4423', '#4A235A', '#1E8449', '#922B21', '#283747', '#7D6608', '#1A5276', '#6C3483', '#117A65'];

const name = () => `${pick(firstNames)} ${pick(lastNames)}`;
const addr = () => `${randInt(100, 9999)} ${pick(streets)}, ${pick(cities)} ${randInt(10000, 99999)}`;
const phone = () => `+1 (${randInt(200, 999)}) ${randInt(100, 999)}-${randInt(1000, 9999)}`;
const email = (n: string) => `${n.toLowerCase().replace(/[^a-z]/g, '').slice(0, 8)}@${pick(domains)}`;
const companyEmail = (c: string) => `${pick(['billing', 'info', 'hello', 'contact', 'support'])}@${c.toLowerCase().replace(/[^a-z]/g, '')}.com`;
const invoiceNum = () => `INV-${new Date().getFullYear()}-${String(randInt(1, 999)).padStart(3, '0')}`;
const receiptNum = () => `R-${Date.now().toString().slice(-8)}-${String(randInt(1, 999)).padStart(3, '0')}`;

const dateStr = () => {
  const d = new Date();
  d.setDate(d.getDate() - randInt(0, 14));
  return d.toISOString().slice(0, 10);
};
const dueDateStr = () => {
  const d = new Date();
  d.setDate(d.getDate() + randInt(14, 45));
  return d.toISOString().slice(0, 10);
};
const formalDate = () => {
  const d = new Date();
  d.setDate(d.getDate() - randInt(0, 7));
  return d.toLocaleDateString('en-US', { year: 'numeric', month: 'long', day: 'numeric' });
};
const dateTimeStr = () => {
  const d = new Date();
  d.setMinutes(d.getMinutes() - randInt(0, 600));
  return `${d.toISOString().slice(0, 10)} ${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`;
};

// --- Invoice ---

const invoiceServices = [
  { description: 'Web Development', rateRange: [100, 200] },
  { description: 'Mobile App Development', rateRange: [120, 250] },
  { description: 'UI/UX Design', rateRange: [80, 160] },
  { description: 'API Integration', rateRange: [100, 180] },
  { description: 'Database Architecture', rateRange: [130, 220] },
  { description: 'QA Testing', rateRange: [70, 130] },
  { description: 'DevOps Setup', rateRange: [110, 200] },
  { description: 'Cloud Migration', rateRange: [140, 250] },
  { description: 'Security Audit', rateRange: [150, 280] },
  { description: 'Technical Consulting', rateRange: [120, 300] },
  { description: 'Server Setup', rateRange: [90, 200] },
  { description: 'Performance Optimization', rateRange: [100, 220] },
];

export function randomInvoiceData() {
  const comp = pick(companies);
  const clientName = name();
  const shuffled = [...invoiceServices].sort(() => Math.random() - 0.5);
  const count = randInt(3, 5);
  const items = shuffled.slice(0, count).map(s => {
    const qty = randInt(2, 50);
    const rate = randInt(s.rateRange[0]!, s.rateRange[1]!);
    return { description: s.description, quantity: qty, rate: fmt(rate), amount: fmt(qty * rate) };
  });
  const subtotal = items.reduce((s, i) => s + parseFloat(i.amount.replace(/[$,]/g, '')), 0);
  const taxRate = pick([0.06, 0.07, 0.08, 0.1]);
  const tax = Math.round(subtotal * taxRate * 100) / 100;
  const discount = randInt(0, 1) ? Math.round(subtotal * pick([0.05, 0.1, 0.15]) * 100) / 100 : 0;
  const total = subtotal + tax - discount;

  return {
    data: {
      companyName: comp,
      companyAddress: addr(),
      companyEmail: companyEmail(comp),
      companyPhone: phone(),
      clientName,
      clientAddress: addr(),
      clientEmail: email(clientName),
      invoiceNumber: invoiceNum(),
      invoiceDate: dateStr(),
      dueDate: dueDateStr(),
      items,
      subtotal: fmt(subtotal),
      tax: fmt(tax),
      discount: discount ? `-${fmt(discount)}` : '$0.00',
      total: fmt(total),
      notes: pick([
        'Payment due within 30 days. Late payments subject to 2% monthly fee.',
        'Please include invoice number on all payments.',
        'Thank you for your business! Net 30 terms apply.',
        'Wire transfer preferred. Contact us for payment details.',
      ]),
      paymentTerms: `Bank transfer to ${comp}, Account: ${randInt(1000, 9999)}-${randInt(1000, 9999)}-${randInt(1000, 9999)}`,
    },
    style: { primaryColor: pick(colors), accentColor: pick(colors) },
  };
}

// --- Receipt ---

const receiptItems = [
  { description: 'Cappuccino', range: [4, 7] },
  { description: 'Espresso', range: [3, 5] },
  { description: 'Green Tea Latte', range: [4, 6] },
  { description: 'Blueberry Muffin', range: [3, 5] },
  { description: 'Croissant', range: [3, 5] },
  { description: 'Avocado Toast', range: [8, 14] },
  { description: 'Caesar Salad', range: [9, 15] },
  { description: 'Iced Americano', range: [4, 6] },
  { description: 'Chicken Wrap', range: [8, 12] },
  { description: 'Fresh Juice', range: [5, 8] },
  { description: 'Chocolate Cake', range: [5, 8] },
  { description: 'Bagel with Cream Cheese', range: [4, 7] },
];

const shopNames = ['Coffee House', 'Urban Brew', 'Bean & Leaf', 'The Daily Grind', 'Sunrise Café', 'Copper Kettle', 'Mosaic Bistro', 'The Green Spoon', 'Harbor Deli', 'Velvet Roast'];

export function randomReceiptData() {
  const biz = pick(shopNames);
  const shuffled = [...receiptItems].sort(() => Math.random() - 0.5);
  const count = randInt(2, 5);
  const items = shuffled.slice(0, count).map(i => {
    const qty = randInt(1, 3);
    const price = randInt(i.range[0]! * 100, i.range[1]! * 100) / 100;
    return { description: i.description, quantity: qty, price: fmt(price) };
  });
  const subtotal = items.reduce((s, i) => s + i.quantity * parseFloat(i.price.replace(/[$,]/g, '')), 0);
  const tax = Math.round(subtotal * pick([0.06, 0.07, 0.08, 0.1]) * 100) / 100;

  return {
    data: {
      businessName: biz,
      businessAddress: addr(),
      receiptNumber: receiptNum(),
      date: dateTimeStr(),
      customerName: name(),
      items,
      subtotal: fmt(subtotal),
      tax: fmt(tax),
      total: fmt(subtotal + tax),
      paymentMethod: pick([
        `Visa ending in ${randInt(1000, 9999)}`,
        `Mastercard ending in ${randInt(1000, 9999)}`,
        'Apple Pay',
        'Google Pay',
        'Cash',
      ]),
    },
    style: { primaryColor: pick(colors) },
  };
}

// --- Contract ---

const contractTitles = [
  'Software Development Agreement',
  'Consulting Services Agreement',
  'Non-Disclosure Agreement',
  'Independent Contractor Agreement',
  'Service Level Agreement',
  'Technology License Agreement',
];

const scopeTexts = [
  'The Developer agrees to design, develop, and deliver a mobile application as described in Exhibit A. The application shall include user authentication, data synchronization, and reporting features.',
  'The Contractor shall provide consulting services related to system architecture, technology selection, and implementation strategy as outlined in the Statement of Work.',
  'The Service Provider agrees to deliver a cloud-based platform with API integration, automated testing, and continuous deployment capabilities per the attached specifications.',
  'The Developer shall create a web application featuring real-time collaboration, document management, and analytics dashboards according to the requirements document.',
];

const compensationTexts = (amount: string) => [
  `The Client agrees to pay a total fee of ${amount}, payable in three installments: 30% upon signing, 40% upon delivery of beta version, and 30% upon final acceptance.`,
  `Compensation for services shall be ${amount}, billed monthly in equal installments over the project duration. Payment is due within 15 days of invoice receipt.`,
  `The agreed project fee is ${amount}. An initial deposit of 25% is due upon execution of this agreement, with the remainder payable upon completion milestones.`,
];

const timelineTexts = () => {
  const start = new Date();
  start.setDate(start.getDate() + randInt(7, 30));
  const end = new Date(start);
  end.setMonth(end.getMonth() + randInt(3, 8));
  const s = start.toLocaleDateString('en-US', { year: 'numeric', month: 'long', day: 'numeric' });
  const e = end.toLocaleDateString('en-US', { year: 'numeric', month: 'long', day: 'numeric' });
  return `The project shall commence on ${s}, and the final deliverable shall be completed no later than ${e}. Milestones and deadlines are detailed in Exhibit B.`;
};

export function randomContractData() {
  const pA = pick(companies);
  let pB = pick(companies);
  while (pB === pA) pB = pick(companies);
  const amount = fmt(randInt(20, 200) * 1000);

  return {
    data: {
      title: pick(contractTitles),
      date: formalDate(),
      partyA: pA,
      partyAAddress: addr(),
      partyB: pB,
      partyBAddress: addr(),
      sections: [
        { heading: 'Scope of Work', content: pick(scopeTexts) },
        { heading: 'Compensation', content: pick(compensationTexts(amount)) },
        { heading: 'Timeline', content: timelineTexts() },
        { heading: 'Intellectual Property', content: 'All intellectual property rights in the deliverables shall transfer to the Client upon full payment. The Developer retains the right to use general knowledge and techniques gained during the project.' },
        { heading: 'Confidentiality', content: 'Both parties agree to maintain the confidentiality of all proprietary information exchanged during the term of this agreement and for a period of two years following its termination.' },
      ],
      signatureDate: formalDate(),
    },
    style: { primaryColor: pick(colors) },
  };
}

// --- Letter ---

const subjects = [
  'Partnership Proposal',
  'Business Collaboration Inquiry',
  'Project Follow-Up',
  'Introduction & Opportunity',
  'Proposal for Joint Venture',
  'Request for Meeting',
  'Service Offering',
];

const letterBodies = (senderCompany: string, recipientName: string) => [
  `I am writing to express our interest in establishing a strategic partnership between our organizations. After reviewing your company's impressive portfolio and market position, we believe there are significant opportunities for collaboration.\n\nOur team has identified several areas where our complementary strengths could create substantial value, including joint product development, co-marketing initiatives, and shared technology resources.\n\nI would welcome the opportunity to discuss this proposal in detail at your earliest convenience.`,
  `Following our conversation at the recent industry conference, I wanted to follow up on the exciting possibilities we discussed. At ${senderCompany}, we have been developing solutions that align closely with your strategic direction.\n\nWe have successfully delivered similar projects for several companies in your sector, and I believe our expertise could bring considerable value to your upcoming initiatives.\n\nI would appreciate the chance to schedule a call or meeting to explore this further.`,
  `I hope this letter finds you well. I am reaching out on behalf of ${senderCompany} to introduce our services and explore how we might support your business objectives.\n\nOur team specializes in delivering innovative solutions that drive efficiency and growth. We have a proven track record with organizations of similar scale and complexity.\n\nPlease do not hesitate to reach out if you would like to learn more. I am available at your convenience for a discussion.`,
  `Thank you for taking the time to meet with us last week, ${recipientName.split(' ').pop()}. Our team was excited to learn about your company's vision and upcoming projects.\n\nAfter careful consideration, we have prepared a comprehensive proposal that addresses the key areas you mentioned. We are confident that our approach will deliver measurable results within the timeline discussed.\n\nPlease find the detailed proposal attached, and feel free to contact me with any questions.`,
];

const titles = ['VP of Business Development', 'Director of Partnerships', 'Chief Operating Officer', 'Head of Strategy', 'Managing Director', 'Senior Account Executive'];

export function randomLetterData() {
  const sName = name();
  const rName = name();
  const comp = pick(companies);

  return {
    data: {
      senderName: sName,
      senderAddress: addr(),
      senderPhone: phone(),
      senderEmail: email(sName),
      recipientName: `${pick(['Mr.', 'Ms.', 'Dr.'])} ${rName}`,
      recipientAddress: addr(),
      date: formalDate(),
      subject: pick(subjects),
      greeting: `Dear ${pick(['Mr.', 'Ms.', 'Dr.'])} ${rName.split(' ').pop()},`,
      body: pick(letterBodies(comp, rName)),
      closing: pick(['Sincerely,', 'Best regards,', 'Kind regards,', 'Respectfully,']),
      senderSignatureName: `${sName}, ${pick(titles)}`,
    },
    style: { primaryColor: pick(colors) },
  };
}
