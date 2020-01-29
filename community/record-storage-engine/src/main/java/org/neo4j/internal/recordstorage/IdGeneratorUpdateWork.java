/*
 * Copyright (c) 2002-2020 "Neo4j,"
 * Neo4j Sweden AB [http://neo4j.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.internal.recordstorage;

import java.util.ArrayList;
import java.util.List;

import org.neo4j.internal.id.IdGenerator;
import org.neo4j.internal.id.IdGenerator.Marker;
import org.neo4j.io.pagecache.tracing.cursor.DefaultPageCursorTracer;
import org.neo4j.io.pagecache.tracing.cursor.DefaultPageCursorTracerSupplier;
import org.neo4j.io.pagecache.tracing.cursor.PageCursorTracer;
import org.neo4j.util.concurrent.Work;

import static org.neo4j.io.pagecache.tracing.cursor.DefaultPageCursorTracerSupplier.TRACER_SUPPLIER;

public class IdGeneratorUpdateWork implements Work<IdGenerator,IdGeneratorUpdateWork>
{
    private final List<ChangedIds> changeList = new ArrayList<>();

    IdGeneratorUpdateWork( ChangedIds changes )
    {
        this.changeList.add( changes );
    }

    @Override
    public IdGeneratorUpdateWork combine( IdGeneratorUpdateWork work )
    {
        changeList.addAll( work.changeList );
        return this;
    }

    @Override
    public void apply( IdGenerator idGenerator )
    {
        //TODO: how we can find out who is who here. Do this per transaction instead?
        try ( Marker marker = idGenerator.marker( TRACER_SUPPLIER.get() ) )
        {
            for ( ChangedIds changes : this.changeList )
            {
                changes.accept( marker );
            }
        }
    }
}
