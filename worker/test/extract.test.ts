import { describe, expect, it } from "vitest";
import { fromJsonLd, localToUtc, normFlightNo, parseAi } from "../src/extract";
import { htmlToText } from "../src/gmail";
import { looksLikeBooking, searchQuery } from "../src/importer";

const LD = `<html><head><script type="application/ld+json">
[{"@context":"http://schema.org","@type":"FlightReservation","reservationNumber":"ABC123","reservationStatus":"http://schema.org/ReservationConfirmed",
  "underName":{"@type":"Person","name":"Gaurav D"},"airplaneSeat":"14C",
  "reservationFor":{"@type":"Flight","flightNumber":"1406","airline":{"@type":"Airline","name":"IndiGo","iataCode":"6E"},
    "departureAirport":{"@type":"Airport","iataCode":"DXB"},"departureTime":"2026-12-02T23:45:00+04:00",
    "arrivalAirport":{"@type":"Airport","iataCode":"DEL"},"arrivalTime":"2026-12-03T04:20:00+05:30"}},
 {"@context":"http://schema.org","@type":"FlightReservation","reservationNumber":"ABC123","underName":{"name":"Someone Else"},"airplaneSeat":"14D",
  "reservationFor":{"@type":"Flight","flightNumber":"1406","airline":{"iataCode":"6E"},
    "departureAirport":{"iataCode":"DXB"},"departureTime":"2026-12-02T23:45:00+04:00","arrivalAirport":{"iataCode":"DEL"}}}]
</script></head><body>Your booking</body></html>`;

describe("schema.org flight data", () => {
  it("reads segments, merging one entry per passenger", () => {
    const ex = fromJsonLd(LD)!;
    expect(ex.method).toBe("jsonld");
    expect(ex.cancelled).toBe(false);
    expect(ex.segments).toHaveLength(1);
    const s = ex.segments[0];
    expect(s).toMatchObject({ flightNumber: "6E1406", date: "2026-12-02", from: "DXB", to: "DEL", departureTime: "23:45", arrivalTime: "04:20", arrivalDate: "2026-12-03", pnr: "ABC123" });
    expect(s.passengers).toEqual(["Gaurav D", "Someone Else"]);
    expect(s.seat).toBe("14C, 14D");
  });
  it("notices cancellations", () => {
    expect(fromJsonLd(LD.replace("ReservationConfirmed", "ReservationCancelled"))!.cancelled).toBe(true);
  });
  it("returns null without flight data", () => expect(fromJsonLd("<p>hello</p>")).toBeNull());
});

describe("AI answers are checked", () => {
  it("keeps valid flights and drops made-up ones", () => {
    const ex = parseAi(
      JSON.stringify({
        isBooking: true,
        cancelled: false,
        flights: [
          { flightNumber: "VJ 885", date: "2026-11-25", from: "sgn", to: "BKK", departureTime: "10:05", arrivalTime: "11:40", pnr: "XYZ9", passengers: ["G D"] },
          { flightNumber: "cheap", date: "soon", from: "Bangkok", to: "BKK" },
          { flightNumber: "FD123", date: "2026-11-25", from: "DMK", to: "DMK" },
        ],
      }),
    );
    expect(ex.segments.map((s) => s.flightNumber)).toEqual(["VJ885"]);
    expect(ex.segments[0].from).toBe("SGN");
    expect(ex.isBooking).toBe(true);
  });
  it("treats an unreadable answer as not a booking", () => {
    expect(parseAi("sorry, I can't").isBooking).toBe(false);
    expect(parseAi(JSON.stringify({ isBooking: true, flights: [] })).isBooking).toBe(false);
  });
  it("accepts JSON wrapped in prose", () => {
    const ex = parseAi('Here: {"isBooking":true,"cancelled":false,"flights":[{"flightNumber":"EK512","date":"2026-12-20","from":"DXB","to":"DEL"}]}');
    expect(ex.segments).toHaveLength(1);
  });
});

describe("flight numbers", () => {
  it.each([
    ["6E-1406", "6E1406"],
    ["6e 1406", "6E1406"],
    ["6E01406", "6E1406"],
    ["EK 0512", "EK512"],
    ["AI2256", "AI2256"],
  ])("%s → %s", (raw, want) => expect(normFlightNo(raw)).toBe(want));
  it("rejects things that aren't flight numbers", () => {
    expect(normFlightNo("12345")).toBeNull();
    expect(normFlightNo("hello")).toBeNull();
  });
});

describe("local times", () => {
  it("converts airport-local times to UTC", () => {
    expect(localToUtc("2026-12-02", "23:45", "Asia/Dubai")).toBe("2026-12-02T19:45:00.000Z");
    expect(localToUtc("2026-12-03", "04:20", "Asia/Kolkata")).toBe("2026-12-02T22:50:00.000Z");
    expect(localToUtc("2026-11-25", "10:05", "Asia/Makassar")).toBe("2026-11-25T02:05:00.000Z");
  });
  it("handles daylight saving (Europe)", () => {
    expect(localToUtc("2027-07-01", "12:00", "Europe/London")).toBe("2027-07-01T11:00:00.000Z");
    expect(localToUtc("2027-01-01", "12:00", "Europe/London")).toBe("2027-01-01T12:00:00.000Z");
  });
});

describe("email filters", () => {
  it("spots bookings cheaply", () => {
    expect(looksLikeBooking("Your IndiGo booking is confirmed", "PNR ABC123 6E 1406 DXB-DEL")).toBe(true);
    expect(looksLikeBooking("50% off flights to Bali!", "Book now and save")).toBe(false);
  });
  it("builds a Gmail search with a date", () => {
    expect(searchQuery(10)).toMatch(/after:\d{4}\/\d{2}\/\d{2}$/);
    expect(searchQuery(10)).toContain("goindigo.in");
  });
  it("turns HTML into text", () => {
    expect(htmlToText("<style>x{}</style><table><tr><td>Flight</td><td>6E 1406</td></tr></table>&amp; more")).toBe("Flight | 6E 1406 |\n& more");
  });
});
