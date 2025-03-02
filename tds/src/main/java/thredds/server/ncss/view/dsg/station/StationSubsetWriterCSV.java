/*
 * (c) 1998-2016 University Corporation for Atmospheric Research/Unidata
 */

package thredds.server.ncss.view.dsg.station;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpHeaders;
import thredds.server.ncss.exception.NcssException;
import thredds.server.ncss.view.dsg.HttpHeaderWriter;
import ucar.ma2.Array;
import ucar.nc2.VariableSimpleIF;
import ucar.nc2.ft.FeatureDatasetPoint;
import ucar.nc2.ft.point.StationPointFeature;
import ucar.nc2.ft2.coverage.SubsetParams;
import ucar.nc2.time.CalendarDateFormatter;
import ucar.unidata.geoloc.Station;
import ucar.unidata.util.Format;

/**
 * Created by cwardgar on 2014-05-24.
 */
public class StationSubsetWriterCSV extends AbstractStationSubsetWriter {

  final protected PrintWriter writer;

  public StationSubsetWriterCSV(FeatureDatasetPoint fdPoint, SubsetParams ncssParams, OutputStream out)
      throws NcssException, IOException {
    this(fdPoint, ncssParams, out, 0);
  }

  public StationSubsetWriterCSV(FeatureDatasetPoint fdPoint, SubsetParams ncssParams, OutputStream out,
      int collectionIndex) throws NcssException, IOException {
    super(fdPoint, ncssParams, collectionIndex);
    this.writer = new PrintWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8));
  }

  @Override
  public HttpHeaders getHttpHeaders(String datasetPath, boolean isStream) {
    return HttpHeaderWriter.getHttpHeadersForCSV(datasetPath, isStream);
  }

  @Override
  protected void writeHeader(StationPointFeature stationPointFeat) throws IOException {
    writer.print("time,station,latitude[unit=\"degrees_north\"],longitude[unit=\"degrees_east\"]");
    for (VariableSimpleIF wantedVar : wantedVariables) {
      writer.print(",");
      writer.print(wantedVar.getShortName());
      if (wantedVar.getUnitsString() != null)
        writer.print("[unit=\"" + wantedVar.getUnitsString() + "\"]");
    }
    writer.println();
  }

  @Override
  protected void writeStationPointFeature(StationPointFeature stationPointFeat) throws IOException {
    Station station = stationPointFeat.getStation();

    writer.print(CalendarDateFormatter.toDateTimeStringISO(stationPointFeat.getObservationTimeAsCalendarDate()));
    writer.print(',');
    writer.print(station.getName());
    writer.print(',');
    writer.print(Format.dfrac(station.getLatitude(), 3));
    writer.print(',');
    writer.print(Format.dfrac(station.getLongitude(), 3));

    for (VariableSimpleIF wantedVar : wantedVariables) {
      writer.print(',');
      Array dataArray = stationPointFeat.getDataAll().getArray(wantedVar.getShortName());
      writer.print(dataArray.toString().trim());
    }
    writer.println();
  }

  @Override
  protected void writeFooter() throws IOException {
    writer.flush();
  }
}
